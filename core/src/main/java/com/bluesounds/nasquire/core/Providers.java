package com.bluesounds.nasquire.core;

import java.util.List;

/** 선택 기능은 이후 단계의 공급자가 구현한다. */
public final class Providers {
    private Providers() { }
    public interface EmbeddingProvider { String modelId(); float[] embed(String text); }
    public interface RerankerProvider { List<SearchIndex.Hit> rerank(String query, List<SearchIndex.Hit> hits); }
    public interface OcrProvider { String recognize(byte[] image, String language); }
}
