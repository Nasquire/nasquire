package com.bluesounds.nasquire.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/** 독립 앱용 진입점. 작업자를 먼저 닫고 색인과 색인이 소유한 공급자를 닫는다. */
public final class NasquireCore implements AutoCloseable {
    private volatile AccessPolicy accessPolicy;
    private final SearchIndex index;
    private final IndexingService indexing;
    private final Supplier<List<Source>> sources;
    private NasquireCore(Builder builder) throws IOException {
        sources = builder.sources;
        accessPolicy = builder.accessPolicy;
        index = new SearchIndex(builder.indexFolder);
        index.configureVectors(builder.embedding, builder.store);
        if (index.embeddingStatus().reindexRequired()) index.requestRebuild();
        indexing = new IndexingService(index, builder.maxBytes, builder.maxCharacters, builder.stableMillis,
                builder.extractors, builder.opener, builder.listener);
    }
    public static Builder builder(Path dataFolder) { return new Builder(dataFolder); }
    public void scan() throws IOException { indexing.scan(List.copyOf(sources.get())); }
    public void start(long intervalMillis) { indexing.start(sources, intervalMillis); }
    public List<SearchIndex.Hit> search(String query, AccessScope scope, int limit, String mode) throws IOException {
        return index.search(query, scope, limit, mode);
    }
    public void setAccessPolicy(AccessPolicy policy) { accessPolicy = Objects.requireNonNull(policy); }
    private AccessScope scopeFor(Principal principal) { return accessPolicy.scopeFor(principal, List.copyOf(sources.get())); }
    public List<SearchIndex.Hit> search(String query, int limit) throws IOException { return search(query, Principal.owner(), limit); }
    public List<SearchIndex.Hit> search(String query, Principal principal, int limit) throws IOException { return search(query, principal, limit, null); }
    public List<SearchIndex.Hit> search(String query, Principal principal, int limit, String mode) throws IOException {
        return search(query, scopeFor(principal), limit, mode);
    }
    public List<SearchIndex.Hit> search(String query, AccessScope scope, int limit) throws IOException { return search(query, scope, limit, null); }
    public List<SearchIndex.Hit> document(String path) throws IOException { return document(path, Principal.owner()); }
    public List<SearchIndex.Hit> document(String path, Principal principal) throws IOException { return document(path, scopeFor(principal)); }
    public List<SearchIndex.Hit> document(String path, AccessScope scope) throws IOException { return index.document(path, scope); }
    public void close() throws IOException { indexing.close(); index.close(); }
    public static final class Builder {
        private AccessPolicy accessPolicy = AccessPolicy.open();
        public Builder accessPolicy(AccessPolicy policy) { accessPolicy = Objects.requireNonNull(policy); return this; }
        private Path indexFolder;
        private Supplier<List<Source>> sources = List::of;
        private long maxBytes = 8L * 1024 * 1024, stableMillis = 1000;
        private int maxCharacters = 1_000_000;
        private List<TextExtractor> extractors = List.of(new PdfTextExtractor(), new PlainTextExtractor(), new OfficeTextExtractor());
        private OriginalOpener opener = OriginalOpener.secure();
        private IndexEvent.Listener listener = event -> { };
        private Providers.EmbeddingProvider embedding;
        private VectorStore store;
        private Builder(Path dataFolder) { indexFolder = dataFolder.resolve("index"); }
        public Builder indexFolder(Path path) { indexFolder = Objects.requireNonNull(path); return this; }
        public Builder sources(Supplier<List<Source>> value) { sources = Objects.requireNonNull(value); return this; }
        public Builder limits(long bytes, int characters, long stable) {
            if (bytes < 1 || characters < 1 || stable < 0) throw new IllegalArgumentException("추출 상한 오류");
            maxBytes = bytes; maxCharacters = characters; stableMillis = stable; return this;
        }
        public Builder extractors(List<TextExtractor> value) { extractors = List.copyOf(value); return this; }
        public Builder originalOpener(OriginalOpener value) { opener = Objects.requireNonNull(value); return this; }
        public Builder listener(IndexEvent.Listener value) { listener = Objects.requireNonNull(value); return this; }
        public Builder vectors(Providers.EmbeddingProvider value, VectorStore vectorStore) { embedding = value; store = vectorStore; return this; }
        public NasquireCore build() throws IOException { return new NasquireCore(this); }
    }
}
