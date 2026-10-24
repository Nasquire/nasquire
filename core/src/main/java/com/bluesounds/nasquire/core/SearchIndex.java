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
    private String indexedModel = "off";
    private int indexedDimensions;
    private boolean reindexRequired, rebuilding, rebuildRequested;
    public record EmbeddingStatus(String modelId, int dimensions, String indexedModelId, int indexedDimensions,
            boolean reindexRequired, boolean rebuilding) { }
    private String model() { return embedding == null ? "off" : embedding.modelId(); }
    private int dimensions() { return embedding == null ? 0 : embedding.dimensions(); }
    public synchronized void configureEmbedding(Providers.EmbeddingProvider provider) {
        var old = embedding; embedding = provider;
        if (rebuilding) { rebuilding = false; reindexRequired = true; }
        reindexRequired = reindexRequired || rebuilding || !model().equals(indexedModel) || dimensions() != indexedDimensions;
        if (old != null && old != provider) old.close();
    }
    public synchronized EmbeddingStatus embeddingStatus() {
        return new EmbeddingStatus(model(), dimensions(), indexedModel, indexedDimensions, reindexRequired, rebuilding);
    }
    public synchronized void requestRebuild() { rebuildRequested = true; }
    public synchronized boolean beginRebuild() throws IOException {
        if (!rebuildRequested) return false;
        writer.deleteAll();
        rebuildRequested = false; rebuilding = true; reindexRequired = true;
        refresh();
        return true;
    }
    public synchronized boolean indexingAllowed() { return embedding == null || !reindexRequired || rebuilding; }
    public synchronized void finishRebuild(boolean complete) {
        if (complete && rebuilding) { indexedModel = model(); indexedDimensions = dimensions(); reindexRequired = false; rebuilding = false; }
    }
    public synchronized float[] testEmbedding() {
        if (embedding == null) throw new IllegalStateException("공급자 없음");
        return embedding.embedQuery("연결 시험 / connection test");
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
            }
        }
        writer = new IndexWriter(directory, config);
        searchers = new SearcherManager(writer, new SearcherFactory());
    }

    public synchronized void replace(Source source, String relative, long mtime, List<Chunk> chunks) throws IOException {
        String key = source.id() + ":" + relative;
        if (!indexingAllowed()) throw new IllegalStateException("재색인 필요");
        var documents = new ArrayList<Document>();
        var vectors = new ArrayList<float[]>();
        if (embedding != null) {
            int batch = embedding.batchSize();
            for (int i = 0; i < chunks.size(); i += batch)
                vectors.addAll(embedding.embedBatch(chunks.subList(i, Math.min(i + batch, chunks.size())).stream().map(Chunk::text).toList()));
        }
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
            if (embedding != null) doc.add(new KnnFloatVectorField("embedding", vectors.get(position++), VectorSimilarityFunction.COSINE));
            documents.add(doc);
        }
        writer.updateDocuments(new Term("key", key), documents);
    }

    public synchronized void delete(String path) throws IOException { writer.deleteDocuments(new Term("key", path)); }
    public synchronized void deleteSource(String source) throws IOException { writer.deleteDocuments(new Term("source", source)); }
    public synchronized void refresh() throws IOException {
        writer.setLiveCommitData(Map.of("embedding.model", indexedModel, "embedding.dimensions", String.valueOf(indexedDimensions),
                "embedding.incomplete", String.valueOf(reindexRequired || rebuilding)).entrySet());
        writer.commit(); searchers.maybeRefreshBlocking();
    }

    public List<Hit> search(String query, Set<String> sources, int limit) throws IOException { return search(query, sources, limit, null); }
    public synchronized List<Hit> search(String query, Set<String> sources, int limit, String mode) throws IOException {
        if (mode == null || mode.isBlank()) mode = embedding == null ? "keyword" : "hybrid";
        if (!Set.of("keyword", "vector", "hybrid").contains(mode)) throw new IllegalArgumentException("검색 mode 오류");
        if (query == null || query.isBlank() || query.length() > 1000 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("검색어 1~1000자, 결과 수 1~100이 필요합니다");
        Query textQuery;
        try { textQuery = new QueryParser("text", analyzer).parse(QueryParser.escape(query)); }
        catch (org.apache.lucene.queryparser.classic.ParseException e) { throw new IllegalArgumentException("검색어 오류"); }
        var keyword = new BooleanQuery.Builder().add(textQuery, BooleanClause.Occur.MUST)
                .add(scope(sources), BooleanClause.Occur.FILTER).build();
        if (mode.equals("keyword")) return find(keyword, limit);
        if (embedding == null || reindexRequired || rebuilding) throw new IllegalStateException("공급자 또는 재색인 필요");
        if (sources.isEmpty()) return List.of();
        int candidates = Math.max(100, limit);
        var vector = find(new KnnFloatVectorQuery("embedding", embedding.embedQuery(query), candidates, scope(sources)), candidates);
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

    public void close() throws IOException { if (embedding != null) embedding.close(); searchers.close(); writer.close(); analyzer.close(); directory.close(); }
}
