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
        /** 고정 리비전·SHA-256 로컬 ONNX cross-encoder. download가 true여도 데이터 폴더 안에만 받는다. */
        RerankerProvider localReranker(java.nio.file.Path model, java.nio.file.Path tokenizer, boolean download, java.nio.file.Path dataFolder, int topN, int timeoutSeconds);
        /** 임베딩·리랭크·OCR만 제공하는 ML 전용 서버를 시작한다. 인증 토큰은 설정 파일·환경변수에서만 읽는다. */
        AutoCloseable ml(String[] args, java.util.Map<String, String> environment) throws Exception;
        /** OCR 렌더링·디코딩 자식 프로세스 본체. 종료 코드를 돌려준다. */
        int ocrWorker(String[] args);
    }
    public static FullProvider full() {
        return java.util.ServiceLoader.load(FullProvider.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("full 빌드가 필요합니다 (-Pfull)"));
    }
    public interface OcrProvider { String recognize(byte[] image, String language); }
}
