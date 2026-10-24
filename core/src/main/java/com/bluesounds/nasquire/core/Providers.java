package com.bluesounds.nasquire.core;

import java.util.List;

/** 선택 기능은 이후 단계의 공급자가 구현한다. */
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
    public interface RerankerProvider { List<SearchIndex.Hit> rerank(String query, List<SearchIndex.Hit> hits); }
    public interface OcrProvider { String recognize(byte[] image, String language); }
}
