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
    public record Chunk(int page, int offset, String text) { }
    public record Hit(String source, String sourceName, String path, int page, int offset, String text, long mtime, float score) { }
    private final NIOFSDirectory directory;
    private final KoreanAnalyzer analyzer = new KoreanAnalyzer();
    private final IndexWriter writer;
    private final SearcherManager searchers;

    public SearchIndex(Path folder) throws IOException {
        Files.createDirectories(folder);
        directory = new NIOFSDirectory(folder);
        var config = new IndexWriterConfig(analyzer);
        config.setRAMBufferSizeMB(8);
        config.setSimilarity(new BM25Similarity());
        writer = new IndexWriter(directory, config);
        searchers = new SearcherManager(writer, new SearcherFactory());
    }

    public synchronized void replace(Source source, String relative, long mtime, List<Chunk> chunks) throws IOException {
        String key = source.id() + ":" + relative;
        var documents = new ArrayList<Document>();
        for (Chunk c : chunks) {
            var doc = new Document();
            doc.add(new StringField("key", key, Field.Store.YES));
            doc.add(new StringField("source", source.id(), Field.Store.YES));
            doc.add(new StoredField("sourceName", source.name()));
            doc.add(new StoredField("path", key));
            doc.add(new StoredField("page", c.page()));
            doc.add(new StoredField("offset", c.offset()));
            doc.add(new StoredField("mtime", mtime));
            doc.add(new TextField("text", c.text(), Field.Store.YES));
            documents.add(doc);
        }
        writer.updateDocuments(new Term("key", key), documents);
    }

    public synchronized void delete(String path) throws IOException { writer.deleteDocuments(new Term("key", path)); }
    public synchronized void deleteSource(String source) throws IOException { writer.deleteDocuments(new Term("source", source)); }
    public synchronized void refresh() throws IOException { writer.commit(); searchers.maybeRefreshBlocking(); }

    public List<Hit> search(String query, Set<String> sources, int limit) throws IOException {
        if (query == null || query.isBlank() || query.length() > 1000 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("검색어 1~1000자, 결과 수 1~100이 필요합니다");
        Query textQuery;
        try { textQuery = new QueryParser("text", analyzer).parse(QueryParser.escape(query)); }
        catch (org.apache.lucene.queryparser.classic.ParseException e) { throw new IllegalArgumentException("검색어 오류"); }
        return find(new BooleanQuery.Builder().add(textQuery, BooleanClause.Occur.MUST)
                .add(scope(sources), BooleanClause.Occur.FILTER).build(), limit);
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
                        doc.get("text"), doc.getField("mtime").numericValue().longValue(), hit.score));
            }
            return hits;
        } finally { searchers.release(searcher); }
    }

    public void close() throws IOException { searchers.close(); writer.close(); analyzer.close(); directory.close(); }
}
