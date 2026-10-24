package com.bluesounds.nasquire.core;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingTest {
    @TempDir Path temp;
    private Providers.EmbeddingProvider provider(String id, int dimensions) {
        return new Providers.EmbeddingProvider() {
            public String modelId() { return id; }
            public int dimensions() { return dimensions; }
            public float[] embed(String text) { var vector = new float[dimensions]; vector[text.contains("sea") ? 0 : 1] = 1; return vector; }
        };
    }
    @Test void remoteBatchesOrderNormalizationAndFailures() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new AtomicInteger(200); var calls = new AtomicInteger();
        server.createContext("/v1/embeddings", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("Bearer test-only-value", exchange.getRequestHeaders().getFirst("Authorization"));
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.contains("test-model")); assertTrue(body.contains("sea")); calls.incrementAndGet();
            byte[] reply = (status.get() == 200 ? "{\"data\":[{\"index\":1,\"embedding\":[0,3]},{\"index\":0,\"embedding\":[2,0]}]}" : "secret-response-body").getBytes();
            exchange.sendResponseHeaders(status.get(), reply.length); exchange.getResponseBody().write(reply); exchange.close();
        }); server.start();
        try (var remote = new RemoteEmbeddingProvider("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", "test-model", "test-only-value", 2, 2, 3)) {
            var vectors = remote.embedBatch(List.of("sea", "land"));
            assertArrayEquals(new float[] {1, 0}, vectors.get(0)); assertArrayEquals(new float[] {0, 1}, vectors.get(1));
            assertEquals(1, calls.get());
            status.set(401);
            var error = assertThrows(IllegalStateException.class, () -> remote.embedBatch(List.of("sea", "land")));
            assertFalse(error.toString().contains("test-only-value")); assertFalse(error.toString().contains("secret-response-body"));
            assertThrows(IllegalArgumentException.class, () -> remote.embedBatch(List.of("a", "b", "c")));
        } finally { server.stop(0); }
        assertThrows(IllegalArgumentException.class, () -> RemoteEmbeddingProvider.normalized(new float[] {Float.NaN}));
        assertThrows(IllegalArgumentException.class, () -> RemoteEmbeddingProvider.normalized(new float[] {0, 0}));
    }
    @Test void remoteRejectsWrongDimensionsAndTimesOutWhileReadingBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var slow = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"data\":[{\"index\":0,\"embedding\":[1]}]}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try {
                if (slow.get()) Thread.sleep(1500);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { }
            finally { exchange.close(); }
        }); server.start();
        try (var provider = new RemoteEmbeddingProvider("http://127.0.0.1:" + server.getAddress().getPort(), "test", "", 2, 1, 1)) {
            assertThrows(IllegalStateException.class, () -> provider.embed("sea"));
            slow.set(true); long start = System.nanoTime();
            assertThrows(IllegalStateException.class, () -> provider.embed("sea"));
            assertTrue(System.nanoTime() - start < 1_450_000_000L, "응답 본문에도 타임아웃 적용");
        } finally { server.stop(0); }
    }
    @Test void offModesScopeMetadataAndDimensionChangeRebuild() throws Exception {
        Path root = Files.createDirectory(temp.resolve("source")); Path indexPath = temp.resolve("index");
        var source = new Source("s", "Test", root, List.of(), List.of());
        var chunks = List.of(new SearchIndex.Chunk(2, 0, "sea ocean"), new SearchIndex.Chunk(3, 9, "land forest"));
        try (var index = new SearchIndex(indexPath)) {
            index.replace(source, "test.txt", 1, chunks); index.refresh();
            assertEquals(1, index.search("ocean", Set.of("s"), 5).size());
            assertThrows(IllegalStateException.class, () -> index.search("sea", Set.of("s"), 5, "vector"));
            assertThrows(IllegalArgumentException.class, () -> index.search("sea", Set.of("s"), 5, "bad"));
            index.configureEmbedding(provider("model-a", 2)); assertTrue(index.embeddingStatus().reindexRequired());
            assertEquals(1, index.search("ocean", Set.of("s"), 5, "keyword").size());
            assertThrows(IllegalStateException.class, () -> index.search("sea", Set.of("s"), 5, "hybrid"));
            index.requestRebuild(); assertTrue(index.beginRebuild());
            index.replace(source, "test.txt", 1, chunks); index.finishRebuild(true); index.refresh();
            assertFalse(index.embeddingStatus().reindexRequired());
            var hits = index.search("sea", Set.of("s"), 5, "vector"); assertEquals(2, hits.getFirst().page());
            assertEquals("s:test.txt", hits.getFirst().path()); assertEquals(2, index.search("sea", Set.of("s"), 5).size());
            assertTrue(index.search("sea", Set.of("other"), 5, "hybrid").isEmpty());
        }
        try (var index = new SearchIndex(indexPath)) {
            index.configureEmbedding(provider("model-a", 2)); assertFalse(index.embeddingStatus().reindexRequired());
            index.configureEmbedding(provider("model-b", 2)); assertTrue(index.embeddingStatus().reindexRequired());
            index.configureEmbedding(provider("model-b", 3)); index.requestRebuild(); index.beginRebuild();
            index.replace(source, "test.txt", 1, chunks); index.finishRebuild(false); index.refresh();
            assertTrue(index.embeddingStatus().reindexRequired());
            index.finishRebuild(true); index.refresh(); assertEquals(3, index.embeddingStatus().indexedDimensions());
            assertFalse(index.search("sea", Set.of("s"), 5, "vector").isEmpty());
            index.configureEmbedding(null); assertTrue(index.indexingAllowed()); assertEquals(1, index.search("ocean", Set.of("s"), 5).size());
        }
    }
    @Test void dimensionsOnlyChangeAndIncompleteRestartNeedReindex() throws Exception {
        Path folder = temp.resolve("dimension-index");
        try (var index = new SearchIndex(folder)) {
            index.configureEmbedding(provider("same-model", 2)); index.requestRebuild(); index.beginRebuild();
            index.finishRebuild(true); index.refresh(); assertFalse(index.embeddingStatus().reindexRequired());
            index.configureEmbedding(provider("same-model", 3)); assertTrue(index.embeddingStatus().reindexRequired());
            index.requestRebuild(); index.beginRebuild(); index.refresh();
        }
        try (var index = new SearchIndex(folder)) {
            index.configureEmbedding(provider("same-model", 2));
            assertTrue(index.embeddingStatus().reindexRequired(), "모델이 같아도 중단된 재색인은 복구 필요");
            index.requestRebuild(); index.beginRebuild();
            index.configureEmbedding(provider("fixed-model", 3));
            assertFalse(index.embeddingStatus().rebuilding()); assertTrue(index.embeddingStatus().reindexRequired());
            assertFalse(index.indexingAllowed());
            index.requestRebuild(); index.beginRebuild(); index.finishRebuild(true); index.refresh();
            assertFalse(index.embeddingStatus().reindexRequired());
        }
    }
    @Test void rrfAddsRanksAndKeepsChunkEvidence() {
        var a = new SearchIndex.Hit("s", "S", "s:a", 2, 0, "a", 1, 100);
        var b = new SearchIndex.Hit("s", "S", "s:a", 2, 1200, "b", 1, 0.8f);
        var fused = SearchIndex.rrf(List.of(a, b), List.of(b), 10);
        assertEquals(2, fused.size()); assertEquals(1200, fused.getFirst().offset());
        assertEquals(1.0 / 62 + 1.0 / 61, fused.getFirst().score(), 0.000001);
        assertEquals(1.0 / 61, fused.get(1).score(), 0.000001);
    }
    @Test void modelFilesRequireCorrectShaEvenWhenAlreadyPresent() throws Exception {
        Path model = Files.writeString(temp.resolve("model.onnx"), "invalid model");
        assertThrows(java.io.IOException.class, () -> ModelFiles.verified(model, "", "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> ModelFiles.verified(model, "", "bad"));
        assertThrows(java.io.IOException.class, () -> ModelFiles.verified(temp.resolve("missing"), "", "0".repeat(64)));
        Path data = Files.createDirectory(temp.resolve("data"));
        Path source = Files.createDirectory(temp.resolve("source"));
        assertThrows(java.io.IOException.class, () -> ModelFiles.verified(source.resolve("model.onnx"), "https://example.com/model.onnx", "0".repeat(64), data));
        Files.createSymbolicLink(data.resolve("escape"), source);
        assertThrows(java.io.IOException.class, () -> ModelFiles.verified(data.resolve("escape/model.onnx"), "https://example.com/model.onnx", "0".repeat(64), data));
        try (var files = Files.list(source)) { assertEquals(0, files.count()); }
    }
}
