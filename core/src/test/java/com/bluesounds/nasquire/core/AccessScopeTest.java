package com.bluesounds.nasquire.core;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AccessScopeTest {
    @TempDir Path temp;
    @Test void rejectsNonCanonicalPathsAndCopiesCollections() {
        for (String path : List.of("", "/a", "../a", "a/../b", "a//b", "a/", "./a", "C:/a", "a\\b", "한", "a\0b"))
            assertThrows(IllegalArgumentException.class, () -> new AccessScope(Set.of("s"), Map.of("s", List.of(path))), path);
        var paths = new ArrayList<>(List.of("a/b"));
        var scope = new AccessScope(Set.of("s"), Map.of("s", paths)); paths.clear();
        assertTrue(scope.allows("s", "s:a/b")); assertTrue(scope.allows("s", "s:a/b/c"));
        assertFalse(scope.allows("s", "s:a/bc/c")); assertFalse(scope.allows("other", "other:a/b/c"));
        assertFalse(scope.allows("s", "s:a/b/../secret"));
        assertFalse(AccessScope.sources(Set.of()).allows("s", "s:a/b"));
    }
    private Providers.EmbeddingProvider provider() {
        return new Providers.EmbeddingProvider() {
            public String modelId() { return "test"; }
            public int dimensions() { return 2; }
            public float[] embed(String text) { return new float[]{1, 0}; }
        };
    }
    @Test void keywordHnswRrfAndDocumentsFilterBeforeLimit() throws Exception {
        verify(null, temp.resolve("lucene"));
    }
    @Test void qdrantPayloadAndUntrustedResponsesAreFiltered() throws Exception {
        try (var fake = new FakeQdrant(null)) {
            verify(new QdrantVectorStore(fake.url(), "test", "", 2), temp.resolve("external"));
            String query = fake.requests("/points/query").getFirst().body();
            assertTrue(query.contains("\"access\"")); assertTrue(query.contains("s:a/b"));
            fake.ignoreFilter = true;
            try (var index = new SearchIndex(temp.resolve("external"))) {
                index.configureVectors(provider(), new QdrantVectorStore(fake.url(), "test", "", 2));
                var scope = new AccessScope(Set.of("s"), Map.of("s", List.of("a/b")));
                for (String mode : List.of("vector", "hybrid"))
                    assertTrue(index.search("ocean", scope, 10, mode).stream().allMatch(h -> h.path().equals("s:a/b/yes.txt")));
            }
        }
    }
    @Test void qdrantPairsSourceAndPrefixBeforeCandidateLimit() throws Exception {
        try (var fake = new FakeQdrant(null);
             var store = new QdrantVectorStore(fake.url(), "test", "", 2)) {
            store.reset(2);
            store.replace("scope:more:dir/private.txt", List.of(new VectorStore.Point("scope:more", "scope:more:dir/private.txt", 0, new float[]{1, 0}, 1)), 2);
            store.replace("scope:more:public/ok.txt", List.of(new VectorStore.Point("scope:more", "scope:more:public/ok.txt", 0, new float[]{0, 1}, 1)), 2);
            var scope = new AccessScope(Set.of("scope", "scope:more"), Map.of("scope", List.of("more:dir"), "scope:more", List.of("public")));
            var matches = store.search(new float[]{1, 0}, scope, 1, 1);
            assertEquals(List.of("scope:more:public/ok.txt"), matches.stream().map(VectorStore.Match::key).toList());
        }
    }
    @Test void oldQdrantFormatRequestsRebuildEvenAfterIntermediateRefresh() throws Exception {
        Path folder = temp.resolve("legacy");
        try (var index = new SearchIndex(folder)) {
            index.refresh();
        }
        try (var directory = org.apache.lucene.store.FSDirectory.open(folder);
             var writer = new org.apache.lucene.index.IndexWriter(directory,
                     new org.apache.lucene.index.IndexWriterConfig())) {
            writer.setLiveCommitData(Map.of("vector.store", "qdrant:legacy", "vector.epoch", "1").entrySet());
            writer.commit();
        }
        try (var index = new SearchIndex(folder)) {
            assertTrue(index.embeddingStatus().reindexRequired());
            index.refresh();
        }
        try (var index = new SearchIndex(folder)) {
            assertTrue(index.beginRebuild());
            index.finishRebuild(true); index.refresh();
        }
        try (var index = new SearchIndex(folder)) {
            assertFalse(index.beginRebuild());
            assertFalse(index.embeddingStatus().reindexRequired());
        }
    }
    private void verify(VectorStore store, Path folder) throws Exception {
        try (var index = new SearchIndex(folder)) {
            index.configureVectors(provider(), store); index.requestRebuild(); index.beginRebuild();
            for (String id : List.of("s", "other")) {
                var source = new Source(id, "Test", temp, List.of(), List.of());
                for (String path : List.of("a/b/yes.txt", "a/bc/no.txt", "private/no.txt"))
                    index.replace(source, path, 1, List.of(new SearchIndex.Chunk(1, 0, "ocean")));
            }
            index.finishRebuild(true); index.refresh();
            var scope = new AccessScope(Set.of("s"), Map.of("s", List.of("a/b")));
            for (String mode : List.of("keyword", "vector", "hybrid")) {
                var hits = index.search("ocean", scope, 1, mode);
                assertEquals(List.of("s:a/b/yes.txt"), hits.stream().map(SearchIndex.Hit::path).toList(), mode);
                assertTrue(index.search("ocean", AccessScope.sources(Set.of()), 1, mode).isEmpty());
            }
            assertTrue(index.document("s:a/bc/no.txt", scope).isEmpty());
            assertEquals(1, index.document("s:a/b/yes.txt", scope).size());
            assertEquals(3, index.search("ocean", AccessScope.sources(Set.of("s")), 10, "keyword").size());
        }
    }
}
