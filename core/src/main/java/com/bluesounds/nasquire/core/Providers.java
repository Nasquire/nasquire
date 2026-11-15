package com.bluesounds.nasquire.core;

import java.util.List;

/** lite에는 계약과 원격 구현(임베딩·리랭크·OCR·Docling)만 포함하고 full 구현(내장 ONNX·Tesseract·ML 서버)은 ServiceLoader로 선택한다. */
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
    /**
     * 리랭크 결과. server는 처리한 원격 서버 이름(로컬·실패 시 null), failover는 앞선 서버 실패로 다른 서버가 처리했는지,
     * applied는 순위를 실제로 바꿨는지(false면 입력 순서 그대로) 여부다.
     */
    public record Reranked(List<SearchIndex.Hit> hits, String server, boolean failover, boolean applied) {
        public static Reranked original(List<SearchIndex.Hit> hits) { return new Reranked(hits, null, false, false); }
    }
    public interface RerankerProvider extends AutoCloseable {
        List<SearchIndex.Hit> rerank(String query, List<SearchIndex.Hit> hits);
        default Reranked reranked(String query, List<SearchIndex.Hit> hits) { return new Reranked(rerank(query, hits), null, false, true); }
        default void close() { }
    }
    public interface FullProvider {
        EmbeddingProvider embedding(java.util.Properties settings, java.nio.file.Path dataFolder);
        /** 로컬 Tesseract CLI OCR. 렌더링·디코딩 격리와 쪽 처리는 core {@code ocr.OcrTextExtractor}가 맡는다. */
        OcrProvider tesseract(java.nio.file.Path dataFolder, int timeoutSeconds, int memoryMiB);
        /** 고정 리비전·SHA-256 로컬 ONNX cross-encoder. download가 true여도 데이터 폴더 안에만 받는다. */
        RerankerProvider localReranker(java.nio.file.Path model, java.nio.file.Path tokenizer, boolean download, java.nio.file.Path dataFolder, int topN, int timeoutSeconds);
        /** 임베딩·리랭크·OCR만 제공하는 ML 전용 서버를 시작한다. 인증 토큰은 설정 파일·환경변수에서만 읽는다. */
        AutoCloseable ml(String[] args, java.util.Map<String, String> environment) throws Exception;
    }
    public static FullProvider full() {
        return java.util.ServiceLoader.load(FullProvider.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("full 빌드가 필요합니다 (-Pfull)"));
    }
    /** 쪽·프레임 PNG 하나를 인식한다. 원격 공급자는 실패 시 {@code remote.RemoteException}을 던진다. */
    public interface OcrProvider extends AutoCloseable {
        String recognize(byte[] image, String language);
        /** 마감 시각(System.nanoTime 기준)이 있는 인식(ML 문서 처리). 기본은 공급자 자체 시간 상한만 쓴다. */
        default String recognize(byte[] image, String language, long deadlineNanos) { return recognize(image, language); }
        default void close() { }
    }
}
