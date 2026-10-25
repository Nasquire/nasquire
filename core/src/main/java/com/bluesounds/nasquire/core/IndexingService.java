package com.bluesounds.nasquire.core;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 단일 낮은 우선순위 작업자가 검사·추출·색인을 순서대로 수행한다. */
public final class IndexingService implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(IndexingService.class.getName());
    public record Failure(String path, String reason) { }
    public record Status(String state, int indexed, int pending, int errors, String message, List<Failure> recentFailures) {
        public Status { recentFailures = List.copyOf(recentFailures); }
        public Status(String state, int indexed, int pending, int errors, String message) {
            this(state, indexed, pending, errors, message, List.of());
        }
    }
    private record Stamp(long size, long mtime, long stableSince, boolean indexed) { }
    private final SearchIndex index;
    private final long maxBytes, stableMillis;
    private final int maxCharacters;
    private final List<TextExtractor> extractors;
    private final Map<String, Map<String, Stamp>> snapshots = new HashMap<>();
    private final Map<String, Source> definitions = new HashMap<>();
    private final Map<String, Status> statuses = new ConcurrentHashMap<>();
    private final Set<String> reindex = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "nasquire-indexer"); t.setPriority(Thread.MIN_PRIORITY); t.setDaemon(true); return t;
    });

    public IndexingService(SearchIndex index, long maxBytes, int maxCharacters, long stableMillis, List<TextExtractor> extractors) {
        this.index = index; this.maxBytes = maxBytes; this.maxCharacters = maxCharacters;
        this.stableMillis = stableMillis; this.extractors = List.copyOf(extractors);
    }

    public void start(Supplier<List<Source>> sources, long intervalMillis) {
        // 예외가 밖으로 나가면 scheduleWithFixedDelay가 이후 실행을 영구 취소하므로 Error(메모리 부족 포함)까지 잡아 상태로 남긴다.
        worker.scheduleWithFixedDelay(() -> {
            try { scan(sources.get()); statuses.remove("_system"); }
            catch (Throwable e) { statuses.put("_system", new Status("error", 0, 0, 1, e instanceof OutOfMemoryError ? "검사 실패: 메모리 부족" : "검사 실패")); }
        }, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }
    public void reindex(String id) { reindex.add(id); }
    public Status status(String id) { return statuses.getOrDefault(id, new Status("waiting", 0, 0, 0, "")); }

    public synchronized void scan(List<Source> sources) throws IOException {
        if (index.beginRebuild()) { snapshots.clear(); definitions.clear(); }
        if (!index.indexingAllowed()) return;
        var active = sources.stream().map(Source::id).collect(java.util.stream.Collectors.toSet());
        for (String old : new HashSet<>(definitions.keySet())) {
            if (!active.contains(old)) { index.deleteSource(old); definitions.remove(old); snapshots.remove(old); statuses.remove(old); }
        }
        var completed = new HashMap<String, Status>();
        for (Source source : sources) {
            if (!source.equals(definitions.put(source.id(), source)) || reindex.remove(source.id())) {
                index.deleteSource(source.id()); snapshots.remove(source.id());
            }
            completed.put(source.id(), scanSource(source));
        }
        synchronized (index) {
            index.finishRebuild(completed.values().stream().allMatch(s -> s.state().equals("ready") && s.pending() == 0 && s.errors() == 0));
            index.refresh();
        }
        // 검색자가 새 색인을 읽을 수 있게 된 뒤 완료 상태를 공개한다.
        statuses.putAll(completed);
    }

    private Status scanSource(Source source) {
        var previous = snapshots.computeIfAbsent(source.id(), k -> new HashMap<>());
        var next = new HashMap<String, Stamp>();
        int[] counts = {0, 0, 0};
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
                        if (attrs.size() > maxBytes) throw new IOException("파일 크기 상한 초과");
                        String key = Source.normalized(relative);
                        if (next.containsKey(key)) throw new IOException("정규화 경로 충돌");
                        var old = previous.get(key);
                        long now = System.currentTimeMillis();
                        boolean same = old != null && old.size == attrs.size() && old.mtime == attrs.lastModifiedTime().toMillis();
                        var stamp = new Stamp(attrs.size(), attrs.lastModifiedTime().toMillis(), same ? old.stableSince : now, same && old.indexed);
                        if (!stamp.indexed && now - stamp.stableSince >= stableMillis) {
                            var chunks = new ArrayList<SearchIndex.Chunk>();
                            int[] offset = {0};
                            TextExtractor.LocatedConsumer sink = (page, location, text) -> {
                                for (int i = 0; i < text.length(); i += 1200) {
                                    String chunk = text.substring(i, Math.min(i + 1200, text.length()));
                                    chunks.add(new SearchIndex.Chunk(page, offset[0], chunk, location)); offset[0] += chunk.length();
                                }
                            };
                            Object identity = attrs.fileKey();
                            if (SecureFiles.supported()) {
                                // 루트부터 각 요소를 NOFOLLOW로 열어 고정한 fd만 읽는다. 검사 뒤 중간 디렉터리를 소스 밖 링크로 바꾸거나
                                // FIFO로 바꿔도 소스 밖 내용을 읽거나 멈추지 않는다.
                                try (var pinned = SecureFiles.open(real)) {
                                    var opened = Files.readAttributes(pinned.path(), BasicFileAttributes.class);
                                    if (!java.util.Objects.equals(opened.fileKey(), identity) || opened.size() != stamp.size
                                            || opened.lastModifiedTime().toMillis() != stamp.mtime) throw new IOException("추출 중 파일 변경");
                                    if (opened.size() > maxBytes) throw new IOException("파일 크기 상한 초과");
                                    extractor.get().extractLocated(real, pinned.path(), sink, maxCharacters);
                                    var done = Files.readAttributes(pinned.path(), BasicFileAttributes.class);
                                    if (done.size() != stamp.size || done.lastModifiedTime().toMillis() != stamp.mtime) throw new IOException("추출 중 파일 변경");
                                }
                            } else {
                                throw new IOException("안전한 원본 열기 미지원");
                            }
                            // 추출 도중 변경·교체된 파일은 이번 검사에서 게시하지 않는다.
                            var after = Files.readAttributes(file.toRealPath(), BasicFileAttributes.class);
                            if (!file.toRealPath().equals(real) || !java.util.Objects.equals(after.fileKey(), identity)
                                    || after.size() != stamp.size || after.lastModifiedTime().toMillis() != stamp.mtime)
                                throw new IOException("추출 중 파일 변경");
                            index.replace(source, key, stamp.mtime, chunks);
                            stamp = new Stamp(stamp.size, stamp.mtime, stamp.stableSince, true);
                        }
                        next.put(key, stamp);
                        if (stamp.indexed) counts[0]++; else { counts[1]++; index.delete(source.id() + ":" + key); }
                    } catch (IOException | RuntimeException | OutOfMemoryError | StackOverflowError e) {
                        // 한 파일의 추출 메모리 부족이 소스 전체 검사를 멈추지 않게 그 파일만 오류로 남긴다.
                        counts[2]++;
                        // 예외 메시지·스택에는 절대 경로·문서 내용·원격 응답이 있을 수 있어 출력하지 않는다.
                        Throwable detail = e;
                        for (int depth = 0; depth < 4 && !(detail instanceof ExtractionFailure) && detail.getCause() != null; depth++) detail = detail.getCause();
                        String reason = detail instanceof ExtractionFailure ? e.getClass().getSimpleName() + ": " + detail.getMessage() : e.getClass().getSimpleName();
                        String relativeName = Source.normalized(relative).replaceAll("[\\p{Cntrl}\\p{Cf}]", "?");
                        LOG.log(System.Logger.Level.WARNING, "파일 추출 실패: {0}; {1}", relativeName, reason);
                        if (failures.size() == 5) failures.removeFirst();
                        failures.add(new Failure(relativeName, reason));
                    }
                    statuses.put(source.id(), new Status("scanning", counts[0], counts[1], counts[2], "", failures));
                    return FileVisitResult.CONTINUE;
                }
            });
            for (String deleted : previous.keySet()) if (!next.containsKey(deleted)) index.delete(source.id() + ":" + deleted);
            snapshots.put(source.id(), next);
            return new Status(counts[2] == 0 ? "ready" : "error", counts[0], counts[1], counts[2], counts[2] == 0 ? "" : "일부 파일 추출 실패", failures);
        } catch (IOException | RuntimeException e) {
            return new Status("error", 0, 0, 1, "소스를 읽을 수 없습니다");
        }
    }

    public void close() {
        worker.shutdownNow();
        try { if (!worker.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("색인 작업 종료 시간 초과"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
