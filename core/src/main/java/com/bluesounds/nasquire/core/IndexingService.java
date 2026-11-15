package com.bluesounds.nasquire.core;

import java.io.*;
import com.bluesounds.nasquire.core.internal.IdleHeapReclaimer;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 단일 낮은 우선순위 작업자가 검사·추출·색인을 순서대로 수행한다. */
public final class IndexingService implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(IndexingService.class.getName());
    public record Failure(String path, String reason) { }
    public record Status(String state, int indexed, int pending, int errors, String message, List<Failure> recentFailures, int oversized, List<String> recentOversized) {
        public Status { recentFailures = List.copyOf(recentFailures); recentOversized = List.copyOf(recentOversized); }
        public Status(String state, int indexed, int pending, int errors, String message) {
            this(state, indexed, pending, errors, message, List.of(), 0, List.of());
        }
    }
    /** doclingTries·retryAt: Docling 일시 실패로 1차 결과만 색인한 파일의 재시도 횟수와 다음 재시도 시각(0이면 대기 없음). 메모리에만 둔다. */
    private record Stamp(long size, long mtime, long stableSince, boolean indexed, int doclingTries, long retryAt) {
        Stamp(long size, long mtime, long stableSince, boolean indexed) { this(size, mtime, stableSince, indexed, 0, 0); }
    }
    private volatile DoclingPages docling;
    /** 표·그림 의심 쪽 Docling 변환을 연결한다(null이면 해제). 연결되지 않은 동안에는 판단 객체를 만들지 않아 비용이 없다. */
    public void docling(DoclingPages pages) { docling = pages; }
    private final SearchIndex index;
    private final OriginalOpener opener;
    private final IndexEvent.Listener listener;
    private void event(IndexEvent.Kind kind, String source, String path, long size, long mtime, String version) {
        try { listener.onEvent(new IndexEvent(kind, source, path, size, mtime, version)); }
        catch (RuntimeException e) { LOG.log(System.Logger.Level.WARNING, "색인 이벤트 리스너 실패"); }
    }
    private void deleted(String source) {
        snapshots.getOrDefault(source, Map.of()).forEach((path, stamp) ->
            event(IndexEvent.Kind.DELETED, source, path, stamp.size, stamp.mtime, stamp.size + ":" + stamp.mtime));
    }
    private volatile long maxBytes;
    private final long stableMillis;
    private final int maxCharacters;
    private final List<TextExtractor> extractors;
    private final Map<String, Map<String, Stamp>> snapshots = new HashMap<>();
    private final Map<String, Source> definitions = new HashMap<>();
    private final Map<String, Status> statuses = new ConcurrentHashMap<>();
    private final Set<String> reindex = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "nasquire-indexer"); t.setPriority(Thread.MIN_PRIORITY); t.setDaemon(true); return t;
    });

    private static final int MAX_FOLLOW_UPS = 10;
    private final Map<String, ScheduledFuture<?>> followUps = new HashMap<>();
    private final Map<String, Integer> followUpCounts = new HashMap<>();
    private long extractionAttempts;
    private final IdleHeapReclaimer heapReclaimer = new IdleHeapReclaimer(worker, () -> {
        // G1 등 다른 GC의 정책에는 개입하지 않는다. 운영자가 명시적으로 끌 수도 있다.
        if (Boolean.parseBoolean(System.getProperty("nasquire.indexing.idle-gc", "true"))
                && java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .anyMatch(gc -> gc.getName().equals("MarkSweepCompact"))) System.gc();
    }, 5000, 300000);

    public IndexingService(SearchIndex index, long maxBytes, int maxCharacters, long stableMillis, List<TextExtractor> extractors) {
        this(index, maxBytes, maxCharacters, stableMillis, extractors, OriginalOpener.secure(), event -> { });
    }
    public IndexingService(SearchIndex index, long maxBytes, int maxCharacters, long stableMillis, List<TextExtractor> extractors,
            OriginalOpener opener, IndexEvent.Listener listener) {
        this.opener = Objects.requireNonNull(opener); this.listener = Objects.requireNonNull(listener);
        this.index = index; this.maxBytes = maxBytes; this.maxCharacters = maxCharacters;
        this.stableMillis = stableMillis; this.extractors = List.copyOf(extractors);
    }

    private Supplier<List<Source>> sources;
    private ScheduledFuture<?> periodic;
    private final Set<String> requested = ConcurrentHashMap.newKeySet();
    public synchronized void start(Supplier<List<Source>> sources, long intervalMillis) {
        this.sources = sources;
        reschedule(intervalMillis);
    }
    public synchronized void reschedule(long intervalMillis) {
        if (intervalMillis < 1) throw new IllegalArgumentException("검사 주기 오류");
        if (periodic != null) periodic.cancel(false);
        periodic = worker.scheduleWithFixedDelay(() -> safelyScan(null), 0, intervalMillis, TimeUnit.MILLISECONDS);
    }
    public void maxBytes(long value) { maxBytes = value; }
    public void scanNow(String id) {
        // 실행 중 요청도 최대 다음 한 번으로 합치며 같은 단일 작업자에서 처리한다.
        if (requested.add(id)) worker.execute(() -> { requested.remove(id); safelyScan(id); });
    }
    private synchronized void safelyScan(String id) {
        resetFollowUps(id);
        runScan(id);
    }
    private void resetFollowUps(String id) {
        for (String key : new HashSet<>(followUps.keySet())) {
            if (id == null || id.equals(key)) followUps.remove(key).cancel(false);
        }
        if (id == null) followUpCounts.clear(); else followUpCounts.remove(id);
    }
    private void runScan(String id) {
        try {
            var all = sources.get();
            if (id == null) scan(all);
            else scanSelected(all, id);
            statuses.remove("_system");
        } catch (Throwable e) {
            statuses.put("_system", new Status("error", 0, 0, 1, e instanceof OutOfMemoryError ? "검사 실패: 메모리 부족" : "검사 실패"));
        }
    }
    private synchronized void scanSelected(List<Source> all, String id) throws IOException {
        // 삭제와 재구축 완료 판단에는 전체 소스 목록을 사용한다.
        scanInternal(all, id);
    }
    public void reindex(String id) {
        reindex.add(id);
        if (sources != null) scanNow(id);
    }
    private void scheduleFollowUp(String id) {
        if (sources == null || worker.isShutdown() || followUps.containsKey(id)
                || followUpCounts.getOrDefault(id, 0) >= MAX_FOLLOW_UPS) return;
        var pending = snapshots.getOrDefault(id, Map.of()).values().stream().filter(s -> !s.indexed).toList();
        if (pending.isEmpty()) return;
        long due = pending.stream().mapToLong(s -> s.stableSince + stableMillis).min().orElseThrow();
        followUps.put(id, worker.schedule(() -> {
            synchronized (this) {
                followUps.remove(id);
                followUpCounts.merge(id, 1, Integer::sum);
                runScan(id);
            }
        }, Math.max(25, due - System.currentTimeMillis() + 25), TimeUnit.MILLISECONDS));
    }
    public Status status(String id) { return statuses.getOrDefault(id, new Status("waiting", 0, 0, 0, "")); }

    public synchronized void scan(List<Source> sources) throws IOException {
        scanInternal(sources, null);
    }

    private void scanInternal(List<Source> sources, String selected) throws IOException {
        long before = extractionAttempts;
        try { scanCycle(sources, selected); }
        finally {
            if (extractionAttempts != before) heapReclaimer.afterExtraction();
        }
    }

    private void scanCycle(List<Source> sources, String selected) throws IOException {
        if (index.beginRebuild()) {
            snapshots.clear(); definitions.clear(); resetFollowUps(null);
            selected = null;
        }
        if (!index.indexingAllowed()) return;
        var removed = new HashSet<String>();
        var active = sources.stream().map(Source::id).collect(java.util.stream.Collectors.toSet());
        for (String old : new HashSet<>(definitions.keySet())) {
            if (!active.contains(old)) {
                index.deleteSource(old); deleted(old); definitions.remove(old); snapshots.remove(old); removed.add(old);
                var pages = docling; if (pages != null) pages.retryWaiting(old, 0);
            }
        }
        var completed = new HashMap<String, Status>();
        for (Source source : sources) {
            if (selected != null && !selected.equals(source.id())) continue;
            var previousDefinition = definitions.put(source.id(), source);
            // "항상 Docling"만 바뀌면 범위는 같으므로 기존 결과를 지우지 않고 재색인한다.
            boolean changed = !source.sameScope(previousDefinition);
            boolean requestedReindex = reindex.remove(source.id())
                    || (!changed && previousDefinition.alwaysDocling() != source.alwaysDocling());
            if (changed) {
                // 루트·범위 변경과 시작 시에는 이전 범위의 문서를 먼저 정리한다.
                index.deleteSource(source.id()); deleted(source.id()); snapshots.remove(source.id());
            } else if (requestedReindex) {
                var previous = snapshots.get(source.id());
                if (previous != null) previous.replaceAll((key, stamp) ->
                        new Stamp(stamp.size, stamp.mtime, System.currentTimeMillis(), false));
            }
            completed.put(source.id(), scanSource(source));
            scheduleFollowUp(source.id());
        }
        synchronized (index) {
            index.finishRebuild(sources.stream().allMatch(source -> {
                var status = completed.getOrDefault(source.id(), statuses.get(source.id()));
                return status != null && status.state().equals("ready") && status.pending() == 0 && status.errors() == 0;
            }));
            index.refresh();
        }
        // 검색자가 새 색인을 읽을 수 있게 된 뒤 완료 상태를 공개한다.
        statuses.putAll(completed);
        removed.forEach(statuses::remove);
    }

    private Status scanSource(Source source) {
        var previous = snapshots.computeIfAbsent(source.id(), k -> new HashMap<>());
        var next = new HashMap<String, Stamp>();
        int[] counts = {0, 0, 0, 0};
        var oversized = new ArrayList<String>();
        long limit = maxBytes;
        var failures = new ArrayList<Failure>();
        statuses.put(source.id(), new Status("scanning", 0, 0, 0, ""));
        try {
            Path root = source.root().toRealPath();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && !source.acceptsDirectory(root.relativize(dir))) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException { throw e; }
                public FileVisitResult visitFile(Path file, BasicFileAttributes ignored) throws IOException {
                    Path relative = root.relativize(file);
                    if (!source.accepts(relative)) return FileVisitResult.CONTINUE;
                    var extractor = extractors.stream().filter(x -> x.supports(file)).findFirst();
                    if (extractor.isEmpty()) return FileVisitResult.CONTINUE;
                    try {
                        Path real = file.toRealPath();
                        if (!real.startsWith(root) || !Files.isRegularFile(real)) return FileVisitResult.CONTINUE;
                        var attrs = Files.readAttributes(real, BasicFileAttributes.class);
                        if (attrs.size() > limit) {
                            counts[3]++;
                            if (oversized.size() == 5) oversized.removeFirst();
                            oversized.add(Source.normalized(relative).replaceAll("[\\p{Cntrl}\\p{Cf}]", "?"));
                            statuses.put(source.id(), new Status("scanning", counts[0], counts[1], counts[2], "", failures, counts[3], oversized));
                            return FileVisitResult.CONTINUE;
                        }
                        String key = Source.normalized(relative);
                        if (next.containsKey(key)) throw new IOException("정규화 경로 충돌");
                        var old = previous.get(key);
                        long now = System.currentTimeMillis();
                        boolean same = old != null && old.size == attrs.size() && old.mtime == attrs.lastModifiedTime().toMillis();
                        var pages = docling;
                        // Docling 일시 실패로 1차 결과만 색인한 파일은 대기 시각이 지난 뒤의 검사에서 다시 처리한다(연결돼 있을 때만).
                        boolean retryDue = same && old.indexed && old.retryAt > 0 && now >= old.retryAt && pages != null && pages.connected();
                        var stamp = new Stamp(attrs.size(), attrs.lastModifiedTime().toMillis(), same ? old.stableSince : now, same && old.indexed && !retryDue,
                                same ? old.doclingTries : 0, same ? old.retryAt : 0);
                        if (!stamp.indexed && now - stamp.stableSince >= stableMillis) {
                            extractionAttempts++;
                            var parts = new ArrayList<DoclingPages.Part>();
                            TextExtractor.LocatedConsumer sink = (page, location, text) -> parts.add(new DoclingPages.Part(page, location, text));
                            var check = pages == null ? null : pages.check(real, source.alwaysDocling());
                            var outcome = new DoclingPages.Outcome(parts, false);
                            Object identity = attrs.fileKey();
                            {
                                // 루트부터 각 요소를 NOFOLLOW로 열어 고정한 fd만 읽는다. 검사 뒤 중간 디렉터리를 소스 밖 링크로 바꾸거나
                                // FIFO로 바꿔도 소스 밖 내용을 읽거나 멈추지 않는다.
                                try (var pinned = opener.open(real)) {
                                    var opened = Files.readAttributes(pinned.path(), BasicFileAttributes.class);
                                    if (!java.util.Objects.equals(opened.fileKey(), identity) || opened.size() != stamp.size
                                            || opened.lastModifiedTime().toMillis() != stamp.mtime) throw new IOException("추출 중 파일 변경");
                                    if (opened.size() > limit) throw new IOException("파일 크기 상한 초과");
                                    extractor.get().extractLocated(real, pinned.path(), sink, maxCharacters, check);
                                    if (check != null && !check.suspects().isEmpty())
                                        outcome = pages.refine(Source.normalized(relative).replaceAll("[\\p{Cntrl}\\p{Cf}]", "?"), real, pinned.path(), check, parts, maxCharacters);
                                    var done = Files.readAttributes(pinned.path(), BasicFileAttributes.class);
                                    if (done.size() != stamp.size || done.lastModifiedTime().toMillis() != stamp.mtime) throw new IOException("추출 중 파일 변경");
                                }
                            }
                            // 추출 도중 변경·교체된 파일은 이번 검사에서 게시하지 않는다.
                            var after = Files.readAttributes(file.toRealPath(), BasicFileAttributes.class);
                            if (!file.toRealPath().equals(real) || !java.util.Objects.equals(after.fileKey(), identity)
                                    || after.size() != stamp.size || after.lastModifiedTime().toMillis() != stamp.mtime)
                                throw new IOException("추출 중 파일 변경");
                            var chunks = new ArrayList<SearchIndex.Chunk>();
                            int offset = 0;
                            for (var part : outcome.parts()) {
                                String text = part.text();
                                for (int i = 0; i < text.length(); i += 1200) {
                                    String chunk = text.substring(i, Math.min(i + 1200, text.length()));
                                    chunks.add(new SearchIndex.Chunk(part.page(), offset, chunk, part.location())); offset += chunk.length();
                                }
                            }
                            index.replace(source, key, stamp.mtime, chunks);
                            event(old != null && old.indexed ? IndexEvent.Kind.UPDATED : IndexEvent.Kind.ADDED,
                                    source.id(), key, stamp.size, stamp.mtime, stamp.size + ":" + stamp.mtime);
                            int tries = outcome.retry() ? stamp.doclingTries + 1 : 0;
                            // 재시도 상한을 넘으면 대기 시각을 지워 1차 결과로 둔다(파일이 바뀌면 다시 0부터).
                            long retryAt = outcome.retry() && tries <= pages.maxRetries() ? System.currentTimeMillis() + pages.retryDelay(tries) : 0;
                            stamp = new Stamp(stamp.size, stamp.mtime, stamp.stableSince, true, tries, retryAt);
                        }
                        next.put(key, stamp);
                        if (stamp.indexed) counts[0]++; else counts[1]++;
                    } catch (IOException | RuntimeException | OutOfMemoryError | StackOverflowError e) {
                        // 한 파일의 추출 메모리 부족이 소스 전체 검사를 멈추지 않게 그 파일만 오류로 남긴다.
                        counts[2]++;
                        event(IndexEvent.Kind.FAILED, source.id(), Source.normalized(relative), ignored.size(), ignored.lastModifiedTime().toMillis(),
                                ignored.size() + ":" + ignored.lastModifiedTime().toMillis());
                        // 예외 메시지·스택에는 절대 경로·문서 내용·원격 응답이 있을 수 있어 출력하지 않는다.
                        Throwable detail = e;
                        for (int depth = 0; depth < 4 && !(detail instanceof ExtractionFailure) && detail.getCause() != null; depth++) detail = detail.getCause();
                        String reason = detail instanceof ExtractionFailure ? e.getClass().getSimpleName() + ": " + detail.getMessage() : e.getClass().getSimpleName();
                        String relativeName = Source.normalized(relative).replaceAll("[\\p{Cntrl}\\p{Cf}]", "?");
                        LOG.log(System.Logger.Level.WARNING, "파일 추출 실패: {0}; {1}", relativeName, reason);
                        if (failures.size() == 5) failures.removeFirst();
                        failures.add(new Failure(relativeName, reason));
                    }
                    statuses.put(source.id(), new Status("scanning", counts[0], counts[1], counts[2], "", failures, counts[3], oversized));
                    return FileVisitResult.CONTINUE;
                }
            });
            for (String deleted : previous.keySet()) if (!next.containsKey(deleted)) {
                index.delete(source.id() + ":" + deleted);
                var old = previous.get(deleted);
                event(IndexEvent.Kind.DELETED, source.id(), deleted, old.size, old.mtime, old.size + ":" + old.mtime);
            }
            snapshots.put(source.id(), next);
            var pages = docling;
            if (pages != null) pages.retryWaiting(source.id(), (int)next.values().stream().filter(s -> s.retryAt > 0).count());
            return new Status(counts[2] == 0 ? "ready" : "error", counts[0], counts[1], counts[2], counts[2] == 0 ? "" : "일부 파일 추출 실패", failures, counts[3], oversized);
        } catch (IOException | RuntimeException e) {
            return new Status("error", 0, 0, 1, "소스를 읽을 수 없습니다");
        }
    }

    public void close() {
        // 정상 종료에서는 진행 중 Lucene 파일 I/O를 인터럽트하지 않는다.
        synchronized (this) {
            if (periodic != null) periodic.cancel(false);
            resetFollowUps(null);
            worker.shutdown();
        }
        heapReclaimer.cancel();
        try {
            if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                if (!worker.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("색인 작업 종료 시간 초과");
            }
        } catch (InterruptedException e) { worker.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
