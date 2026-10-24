package com.bluesounds.nasquire.core;

import java.util.List;

/** lite에는 계약만 포함하고 full 구현은 ServiceLoader로 선택한다. */
public final class Providers {
    private Providers() { }
    public interface EmbeddingProvider extends AutoCloseable {
        String modelId();
        int dimensions();
        float[] embed(String text);
        default float[] embedQuery(String text) { return embed(text); }
        default List<float[]> embedBatch(List<String> texts) { return texts.stream().map(this::embed).toList(); }
        default int batchSize() { return 1; }
        default void close() { }
    }
    public interface RerankerProvider extends AutoCloseable {
        List<SearchIndex.Hit> rerank(String query, List<SearchIndex.Hit> hits);
        default void close() { }
    }
    public interface FullProvider {
        EmbeddingProvider embedding(java.util.Properties settings, java.nio.file.Path dataFolder);
        RerankerProvider reranker(String baseUrl, String model, String key, int topN, int timeoutSeconds);
        TextExtractor ocr(java.nio.file.Path dataFolder, int timeoutSeconds, int memoryMiB, int maxPages);
    }
    public static FullProvider full() {
        return java.util.ServiceLoader.load(FullProvider.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("full 빌드가 필요합니다 (-Pfull)"));
    }
    public interface OcrProvider { String recognize(byte[] image, String language); }
}
