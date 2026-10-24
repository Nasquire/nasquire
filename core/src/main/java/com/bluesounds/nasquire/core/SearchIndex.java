package com.bluesounds.nasquire.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.store.NIOFSDirectory;

public final class SearchIndex implements AutoCloseable {
    public record Chunk(int page, int offset, String text, Location location) {
        public Chunk(int page, int offset, String text) { this(page, offset, text, Location.page(page)); }
    }
    public record Hit(String source, String sourceName, String path, int page, int offset, String text, long mtime, float score, Location location) {
        public Hit(String source, String sourceName, String path, int page, int offset, String text, long mtime, float score) {
            this(source, sourceName, path, page, offset, text, mtime, score, Location.page(page));
        }
    }
    private static final com.google.gson.Gson JSON = new com.google.gson.Gson();
    private final NIOFSDirectory directory;
    private final KoreanAnalyzer analyzer = new KoreanAnalyzer();
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private Providers.EmbeddingProvider embedding;
    private VectorStore store;
    private String indexedModel = "off", indexedStore = "lucene";
    private int indexedDimensions;
    private boolean reindexRequired, rebuilding, rebuildRequested;
    /**
     * 벡터 구성 세대. 공급자·저장소 교체와 재색인 시작 때 증가한다. 원격 호출(임베딩·Qdrant)은 이 객체의 잠금 밖에서 수행하고,
     * 결과를 게시·사용하기 전에 잠금 안에서 세대가 그대로인지 확인한다.
     */
    private long generation;
    /** 잠금 밖에서 공급자·저장소를 쓰는 작업 수. 교체된 객체는 사용이 끝난 뒤 닫는다. */
    private int active;
    private final List<AutoCloseable> retired = new ArrayList<>();
    public record EmbeddingStatus(String modelId, int dimensions, String indexedModelId, int indexedDimensions,
            boolean reindexRequired, boolean rebuilding, String vectorStore, String indexedVectorStore) { }
    private String model() { return embedding == null ? "off" : embedding.modelId(); }
    private int dimensions() { return embedding == null ? 0 : embedding.dimensions(); }
    /** 저장소 식별자. 외부 저장소는 임베딩이 켜져 있을 때만 사용한다. */
    private String storeId() { return store == null ? "lucene" : store.id(); }
    private boolean external() { return store != null && embedding != null; }
    public synchronized void configureEmbedding(Providers.EmbeddingProvider provider) { configureVectors(provider, store); }
    /** 모델·차원·벡터 저장소 중 하나라도 색인 당시와 다르면 재색인 전까지 벡터 검색을 막는다. */
    public synchronized void configureVectors(Providers.EmbeddingProvider provider, VectorStore vectorStore) {
        var old = embedding; var oldStore = store; embedding = provider; store = vectorStore; generation++;
        if (rebuilding) { rebuilding = false; reindexRequired = true; }
        reindexRequired = reindexRequired || rebuilding || !model().equals(indexedModel) || dimensions() != indexedDimensions
                || embedding != null && !storeId().equals(indexedStore);
        if (old != null && old != provider) retired.add(old);
        if (oldStore != null && oldStore != vectorStore) retired.add(oldStore);
        if (active == 0) closeRetired();
    }
    private void closeRetired() {
        for (var c : retired) { try { c.close(); } catch (Exception ignored) { } }
        retired.clear();
    }
    private synchronized void exit() { if (--active == 0) closeRetired(); }
    private synchronized void checkGeneration(long expected) {
        if (expected != generation) throw new IllegalStateException("벡터 설정 변경, 다시 시도");
    }
    public synchronized EmbeddingStatus embeddingStatus() {
        return new EmbeddingStatus(model(), dimensions(), indexedModel, indexedDimensions, reindexRequired, rebuilding,
                store == null ? "lucene" : "qdrant", indexedStore.startsWith("qdrant:") ? "qdrant" : indexedStore);
    }
    public synchronized void requestRebuild() { rebuildRequested = true; }
    public boolean beginRebuild() throws IOException {
        VectorStore target; int dims; long expected;
        synchronized (this) {
            if (!rebuildRequested) return false;
            target = external() ? store : null; dims = dimensions(); expected = generation; active++;
        }
        // 외부 저장소를 잠금 밖에서 먼저 비운다. 실패하면 요청을 유지하고 다음 검사에서 다시 시도한다.
        try { if (target != null) target.reset(dims); } finally { exit(); }
        synchronized (this) {
            // 그 사이 설정이 바뀌었으면 요청을 유지하고 다음 검사에서 새 저장소로 다시 시작한다.
            if (expected != generation || !rebuildRequested) return false;
            writer.deleteAll();
            rebuildRequested = false; rebuilding = true; reindexRequired = true; generation++;
            refresh();
            return true;
        }
    }
    public synchronized boolean indexingAllowed() { return embedding == null || !reindexRequired || rebuilding; }
    public synchronized void finishRebuild(boolean complete) {
        if (complete && rebuilding) { indexedModel = model(); indexedDimensions = dimensions(); indexedStore = embedding == null ? "lucene" : storeId(); reindexRequired = false; rebuilding = false; }
    }
    public float[] testEmbedding() {
        Providers.EmbeddingProvider provider;
        synchronized (this) {
            if (embedding == null) throw new IllegalStateException("공급자 없음");
            provider = embedding; active++;
        }
        try { return provider.embedQuery("연결 시험 / connection test"); } finally { exit(); }
    }

    public SearchIndex(Path folder) throws IOException {
        Files.createDirectories(folder);
        directory = new NIOFSDirectory(folder);
        var config = new IndexWriterConfig(analyzer);
        config.setRAMBufferSizeMB(8);
        config.setSimilarity(new BM25Similarity());
        if (DirectoryReader.indexExists(directory)) {
            try (var reader = DirectoryReader.open(directory)) {
                var meta = reader.getIndexCommit().getUserData();
                indexedModel = meta.getOrDefault("embedding.model", "off");
                indexedDimensions = Integer.parseInt(meta.getOrDefault("embedding.dimensions", "0"));
                reindexRequired = Boolean.parseBoolean(meta.getOrDefault("embedding.incomplete", "false"));
                indexedStore = meta.getOrDefault("vector.store", "lucene");
            }
        }
        writer = new IndexWriter(directory, config);
        searchers = new SearcherManager(writer, new SearcherFactory());
    }

    /** 임베딩·외부 저장소 호출은 잠금 밖에서 하고, Lucene 게시는 세대가 같을 때만 잠금 안에서 한다. */
    public void replace(Source source, String relative, long mtime, List<Chunk> chunks) throws IOException {
        String key = source.id() + ":" + relative;
        Providers.EmbeddingProvider provider; VectorStore target; int dims; long expected;
        synchronized (this) {
            if (!indexingAllowed()) throw new IllegalStateException("재색인 필요");
            provider = embedding; target = external() ? store : null; dims = dimensions(); expected = generation; active++;
        }
        var vectors = new ArrayList<float[]>();
        try {
            if (provider != null) {
                int batch = provider.batchSize();
                for (int i = 0; i < chunks.size(); i += batch)
                    vectors.addAll(provider.embedBatch(chunks.subList(i, Math.min(i + batch, chunks.size())).stream().map(Chunk::text).toList()));
            }
            if (target != null) {
                var points = new ArrayList<VectorStore.Point>();
                for (int i = 0; i < chunks.size(); i++) points.add(new VectorStore.Point(source.id(), key, chunks.get(i).offset(), vectors.get(i)));
                // 외부 점을 먼저 바꾼다. 실패하면 Lucene 문서를 게시하지 않아 파일 오류로 남는다.
                target.replace(key, points, dims);
            }
        } finally { exit(); }
        synchronized (this) {
            // 설정·재색인이 끼어들었으면 게시하지 않는다. 파일 오류로 남아 다음 검사에서 다시 처리한다.
            checkGeneration(expected);
            publish(source, key, mtime, chunks, provider, target != null, vectors);
        }
    }
    private void publish(Source source, String key, long mtime, List<Chunk> chunks, Providers.EmbeddingProvider provider, boolean external,
            List<float[]> vectors) throws IOException {
        var documents = new ArrayList<Document>();
        int position = 0;
        for (Chunk c : chunks) {
            var doc = new Document();
            doc.add(new StringField("key", key, Field.Store.YES));
            doc.add(new StringField("source", source.id(), Field.Store.YES));
            doc.add(new StoredField("sourceName", source.name()));
            doc.add(new StoredField("path", key));
            doc.add(new StoredField("page", c.page()));
            doc.add(new StoredField("location", JSON.toJson(c.location())));
            doc.add(new StoredField("offset", c.offset()));
            doc.add(new StoredField("mtime", mtime));
            doc.add(new TextField("text", c.text(), Field.Store.YES));
            if (external) doc.add(new StringField("chunk", key + "\0" + c.offset(), Field.Store.NO));
            else if (provider != null) doc.add(new KnnFloatVectorField("embedding", vectors.get(position++), VectorSimilarityFunction.COSINE));
            documents.add(doc);
        }
        writer.updateDocuments(new Term("key", key), documents);
    }

    /** Lucene을 먼저 지운 뒤 잠금 밖에서 외부 점을 지운다. 남은 점은 Lucene 조회에서 버려진다. */
    public void delete(String path) throws IOException {
        VectorStore target;
        synchronized (this) { writer.deleteDocuments(new Term("key", path)); target = external() ? store : null; if (target != null) active++; }
        if (target != null) { try { target.deleteKey(path); } finally { exit(); } }
    }
    public void deleteSource(String source) throws IOException {
        VectorStore target;
        synchronized (this) { writer.deleteDocuments(new Term("source", source)); target = external() ? store : null; if (target != null) active++; }
        if (target != null) { try { target.deleteSource(source); } finally { exit(); } }
    }
    public synchronized void refresh() throws IOException {
        writer.setLiveCommitData(Map.of("embedding.model", indexedModel, "embedding.dimensions", String.valueOf(indexedDimensions),
                "embedding.incomplete", String.valueOf(reindexRequired || rebuilding), "vector.store", indexedStore).entrySet());
        writer.commit(); searchers.maybeRefreshBlocking();
    }

    public List<Hit> search(String query, Set<String> sources, int limit) throws IOException { return search(query, sources, limit, null); }
    /** keyword 검색과 원격 호출은 공통 잠금 없이 수행한다. 외부 결과는 세대 확인 뒤 잠금 안의 Lucene 조회로만 본문을 얻는다. */
    public List<Hit> search(String query, Set<String> sources, int limit, String mode) throws IOException {
        if (mode == null || mode.isBlank()) { synchronized (this) { mode = embedding == null ? "keyword" : "hybrid"; } }
        if (!Set.of("keyword", "vector", "hybrid").contains(mode)) throw new IllegalArgumentException("검색 mode 오류");
        if (query == null || query.isBlank() || query.length() > 1000 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("검색어 1~1000자, 결과 수 1~100이 필요합니다");
        Query textQuery;
        try { textQuery = new QueryParser("text", analyzer).parse(QueryParser.escape(query)); }
        catch (org.apache.lucene.queryparser.classic.ParseException e) { throw new IllegalArgumentException("검색어 오류"); }
        var keyword = new BooleanQuery.Builder().add(textQuery, BooleanClause.Occur.MUST)
                .add(scope(sources), BooleanClause.Occur.FILTER).build();
        if (mode.equals("keyword")) return find(keyword, limit);
        Providers.EmbeddingProvider provider; VectorStore target; long expected;
        synchronized (this) {
            if (embedding == null || reindexRequired || rebuilding) throw new IllegalStateException("공급자 또는 재색인 필요");
            if (sources.isEmpty()) return List.of();
            provider = embedding; target = external() ? store : null; expected = generation; active++;
        }
        int candidates = Math.max(100, limit);
        List<Hit> vector;
        try {
            float[] embedded = provider.embedQuery(query);
            var matches = target == null ? null : target.search(embedded, sources, candidates);
            synchronized (this) {
                checkGeneration(expected);
                if (reindexRequired || rebuilding) throw new IllegalStateException("공급자 또는 재색인 필요");
                vector = target != null ? external(matches, sources)
                        : find(new KnnFloatVectorQuery("embedding", embedded, candidates, scope(sources)), candidates);
            }
        } finally { exit(); }
        return mode.equals("vector") ? vector.stream().limit(limit).toList() : rrf(find(keyword, candidates), vector, limit);
    }

    /** 같은 문서의 서로 다른 조각을 합치지 않는다. RRF 상수는 60이다. */
    public static List<Hit> rrf(List<Hit> keyword, List<Hit> vector, int limit) {
        var hits = new LinkedHashMap<String, Hit>(); var scores = new HashMap<String, Double>();
        for (var list : List.of(keyword, vector)) for (int i = 0; i < list.size(); i++) {
            var hit = list.get(i); String key = hit.path() + "\0" + hit.page() + "\0" + hit.offset();
            hits.putIfAbsent(key, hit); scores.merge(key, 1.0 / (60 + i + 1), Double::sum);
        }
        return hits.entrySet().stream().sorted(Comparator.<Map.Entry<String, Hit>>comparingDouble(e -> scores.get(e.getKey())).reversed()
                .thenComparing(Map.Entry::getKey)).limit(limit).map(e -> {
                    var h = e.getValue(); return new Hit(h.source(), h.sourceName(), h.path(), h.page(), h.offset(), h.text(), h.mtime(), scores.get(e.getKey()).floatValue(), h.location());
                }).toList();
    }

    /** 외부 저장소의 조각 식별자를 소스 범위 필터를 건 Lucene 조회로 바꾼다. 범위 밖·삭제된 조각은 버린다. */
    private List<Hit> external(List<VectorStore.Match> matches, Set<String> sources) throws IOException {
        if (matches.isEmpty()) return List.of();
        var ids = new ArrayList<org.apache.lucene.util.BytesRef>();
        for (var m : matches) if (sources.contains(m.source())) ids.add(new org.apache.lucene.util.BytesRef(m.key() + "\0" + m.offset()));
        if (ids.isEmpty()) return List.of();
        var found = new HashMap<String, Hit>();
        for (var h : find(new BooleanQuery.Builder().add(new TermInSetQuery("chunk", ids), BooleanClause.Occur.MUST)
                .add(scope(sources), BooleanClause.Occur.FILTER).build(), ids.size())) found.put(h.path() + "\0" + h.offset(), h);
        var hits = new ArrayList<Hit>();
        for (var m : matches) {
            var h = found.remove(m.key() + "\0" + m.offset());
            if (h == null || !sources.contains(h.source()) || !h.source().equals(m.source())) continue;
            hits.add(new Hit(h.source(), h.sourceName(), h.path(), h.page(), h.offset(), h.text(), h.mtime(), m.score(), h.location()));
        }
        return hits;
    }

    public List<Hit> document(String path, Set<String> sources) throws IOException {
        var hits = find(new BooleanQuery.Builder()
                .add(new TermQuery(new Term("key", path)), BooleanClause.Occur.MUST)
                .add(scope(sources), BooleanClause.Occur.FILTER).build(), 4096);
        return hits.stream().sorted(Comparator.comparingInt(Hit::page).thenComparingInt(Hit::offset)).toList();
    }

    private Query scope(Set<String> sources) {
        if (sources.isEmpty()) return new MatchNoDocsQuery();
        return new TermInSetQuery("source", sources.stream().map(org.apache.lucene.util.BytesRef::new).toList());
    }

    private List<Hit> find(Query query, int limit) throws IOException {
        var searcher = searchers.acquire();
        try {
            var hits = new ArrayList<Hit>();
            for (var hit : searcher.search(query, limit).scoreDocs) {
                var doc = searcher.storedFields().document(hit.doc);
                hits.add(new Hit(doc.get("source"), doc.get("sourceName"), doc.get("path"),
                        doc.getField("page").numericValue().intValue(), doc.getField("offset").numericValue().intValue(),
                        doc.get("text"), doc.getField("mtime").numericValue().longValue(), hit.score, doc.get("location") == null ? Location.page(doc.getField("page").numericValue().intValue()) : JSON.fromJson(doc.get("location"), Location.class)));
            }
            return hits;
        } finally { searchers.release(searcher); }
    }

    public void close() throws IOException {
        synchronized (this) { closeRetired(); }
        if (embedding != null) embedding.close(); if (store != null) store.close(); searchers.close(); writer.close(); analyzer.close(); directory.close(); }
}
