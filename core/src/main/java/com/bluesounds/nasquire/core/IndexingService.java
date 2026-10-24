package com.bluesounds.nasquire.core;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 단일 낮은 우선순위 작업자가 검사·추출·색인을 순서대로 수행한다. */
public final class IndexingService implements AutoCloseable {
    public record Status(String state, int indexed, int pending, int errors, String message) { }
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
        worker.scheduleWithFixedDelay(() -> {
            try { scan(sources.get()); }
            catch (Exception e) { statuses.put("_system", new Status("error", 0, 0, 1, "검사 실패")); }
        }, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }
    public void reindex(String id) { reindex.add(id); }
    public Status status(String id) { return statuses.getOrDefault(id, new Status("waiting", 0, 0, 0, "")); }

    public synchronized void scan(List<Source> sources) throws IOException {
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
        index.refresh();
        // 검색자가 새 색인을 읽을 수 있게 된 뒤 완료 상태를 공개한다.
        statuses.putAll(completed);
    }

    private Status scanSource(Source source) {
        var previous = snapshots.computeIfAbsent(source.id(), k -> new HashMap<>());
        var next = new HashMap<String, Stamp>();
        int[] counts = {0, 0, 0};
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
                        if (attrs.size() > maxBytes) return FileVisitResult.CONTINUE;
                        String key = Source.normalized(relative);
                        if (next.containsKey(key)) throw new IOException("정규화 경로 충돌");
                        var old = previous.get(key);
                        long now = System.currentTimeMillis();
                        boolean same = old != null && old.size == attrs.size() && old.mtime == attrs.lastModifiedTime().toMillis();
                        var stamp = new Stamp(attrs.size(), attrs.lastModifiedTime().toMillis(), same ? old.stableSince : now, same && old.indexed);
                        if (!stamp.indexed && now - stamp.stableSince >= stableMillis) {
                            var chunks = new ArrayList<SearchIndex.Chunk>();
                            int[] offset = {0};
                            extractor.get().extract(real, (page, text) -> {
                                for (int i = 0; i < text.length(); i += 1200) {
                                    String chunk = text.substring(i, Math.min(i + 1200, text.length()));
                                    chunks.add(new SearchIndex.Chunk(page, offset[0], chunk)); offset[0] += chunk.length();
                                }
                            }, maxCharacters);
                            // 추출 도중 변경된 파일은 이번 검사에서 게시하지 않는다.
                            var after = Files.readAttributes(file.toRealPath(), BasicFileAttributes.class);
                            if (!file.toRealPath().equals(real) || after.size() != stamp.size || after.lastModifiedTime().toMillis() != stamp.mtime)
                                throw new IOException("추출 중 파일 변경");
                            index.replace(source, key, stamp.mtime, chunks);
                            stamp = new Stamp(stamp.size, stamp.mtime, stamp.stableSince, true);
                        }
                        next.put(key, stamp);
                        if (stamp.indexed) counts[0]++; else { counts[1]++; index.delete(source.id() + ":" + key); }
                    } catch (IOException | RuntimeException e) { counts[2]++; }
                    return FileVisitResult.CONTINUE;
                }
            });
            for (String deleted : previous.keySet()) if (!next.containsKey(deleted)) index.delete(source.id() + ":" + deleted);
            snapshots.put(source.id(), next);
            return new Status(counts[2] == 0 ? "ready" : "error", counts[0], counts[1], counts[2], counts[2] == 0 ? "" : "일부 파일 추출 실패");
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
