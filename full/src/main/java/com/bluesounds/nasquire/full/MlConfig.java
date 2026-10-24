package com.bluesounds.nasquire.full;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * ML 전용 모드 설정. 우선순위는 명령행 > 환경변수 > 설정 파일이다.
 * 인증 토큰은 명령행에서 받지 않는다(프로세스 목록 노출 방지). 설정 파일·환경변수·토큰 파일만 허용한다.
 */
public record MlConfig(String address, int port, Path data, byte[] tokenHash, String embedding, String embeddingModel,
        String reranker, String rerankerModel, String ocr, int ocrTimeoutSeconds, int ocrMemoryMiB, boolean download,
        Path embeddingModelPath, Path embeddingTokenizerPath, Path rerankerModelPath, Path rerankerTokenizerPath,
        int rerankTimeoutSeconds, int threads) {
    static final String PREFIX = "nasquire.ml.";
    /** 내장 E5 모델 고정 리비전. 앱의 내장 임베딩 기본값과 같다. */
    static final String E5_REVISION = "761b726dd34fb83930e26aab4e9ac3899aa1fa78";
    static final String E5_MODEL_URL = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/" + E5_REVISION + "/onnx/model_quantized.onnx";
    static final String E5_TOKENIZER_URL = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/" + E5_REVISION + "/tokenizer.json";
    static final String E5_MODEL_SHA256 = "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193";
    static final String E5_TOKENIZER_SHA256 = "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39";

    public static MlConfig load(String[] args, Map<String, String> environment) throws IOException {
        var values = new HashMap<String, String>();
        String configFile = argument(args, "nasquire.ml.config");
        if (configFile == null) configFile = environment.get("NASQUIRE_ML_CONFIG");
        if (configFile != null) {
            var file = new Properties();
            try (var in = Files.newInputStream(Path.of(configFile))) { file.load(in); }
            for (String k : file.stringPropertyNames()) values.put(k, file.getProperty(k));
        }
        for (var e : environment.entrySet()) {
            if (!e.getKey().startsWith("NASQUIRE_") && !e.getKey().startsWith("SERVER_")) continue;
            values.put(e.getKey().toLowerCase(Locale.ROOT).replace('_', '.'), e.getValue());
        }
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) continue;
            String key = arg.substring(2, arg.indexOf('='));
            if (key.equals(PREFIX + "token")) throw new IllegalArgumentException("ML 토큰은 명령행으로 받지 않습니다. 설정 파일·환경변수·토큰 파일을 사용하세요");
            values.put(key, arg.substring(arg.indexOf('=') + 1));
        }
        return from(values);
    }
    private static String argument(String[] args, String key) {
        for (String arg : args) if (arg.startsWith("--" + key + "=")) return arg.substring(key.length() + 3);
        return null;
    }
    /** 환경변수 표기(NASQUIRE_ML_TOKEN_FILE)와 점 표기(nasquire.ml.token-file)를 모두 받는다. */
    private static String get(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        if (value == null) value = values.get(key.replace('-', '.'));
        return value == null || value.isBlank() ? fallback : value.trim();
    }
    static MlConfig from(Map<String, String> v) throws IOException {
        if (!"ml".equals(get(v, "nasquire.mode", "ml"))) throw new IllegalArgumentException("ML 모드 설정 오류");
        Path data = Path.of(get(v, "nasquire.data", "./data")).toAbsolutePath().normalize();
        String token = get(v, PREFIX + "token", null), tokenFile = get(v, PREFIX + "token-file", null);
        if (token != null && tokenFile != null) throw new IllegalArgumentException("ML 토큰과 토큰 파일 중 하나만 설정하세요");
        if (tokenFile != null) {
            Path file = Path.of(tokenFile);
            if (Files.size(file) > 4096) throw new IllegalArgumentException("ML 토큰 파일 크기 오류");
            token = Files.readString(file).strip();
        }
        if (token == null || token.length() < 32 || token.length() > 512 || !token.chars().allMatch(c -> c > 0x20 && c < 0x7f))
            throw new IllegalArgumentException("ML 모드는 32~512자 ASCII Bearer 토큰(nasquire.ml.token 또는 nasquire.ml.token-file)이 필요합니다");
        String embedding = choice(get(v, PREFIX + "embedding", "off"), "off", "builtin");
        String reranker = choice(get(v, PREFIX + "reranker", "off"), "off", "builtin");
        String ocr = choice(get(v, PREFIX + "ocr", "off"), "off", "tesseract");
        if (embedding.equals("off") && reranker.equals("off") && ocr.equals("off"))
            throw new IllegalArgumentException("ML 모드에서 임베딩·리랭커·OCR 중 하나 이상을 켜야 합니다");
        Path models = data.resolve("models");
        return new MlConfig(get(v, "server.address", "127.0.0.1"), number(get(v, "server.port", "8080"), 0, 65535), data,
                MlServer.sha256(token), embedding, get(v, PREFIX + "embedding-model", "multilingual-e5-small"),
                reranker, get(v, PREFIX + "reranker-model", OnnxRerankerProvider.NAME), ocr,
                number(get(v, PREFIX + "ocr-timeout-seconds", "15"), 1, 60), number(get(v, PREFIX + "ocr-memory-mib", "512"), 128, 2048),
                Boolean.parseBoolean(get(v, PREFIX + "download", "false")),
                Path.of(get(v, PREFIX + "embedding-model-path", models.resolve("multilingual-e5-small/model-int8.onnx").toString())),
                Path.of(get(v, PREFIX + "embedding-tokenizer-path", models.resolve("multilingual-e5-small/tokenizer.json").toString())),
                Path.of(get(v, PREFIX + "reranker-model-path", OnnxRerankerProvider.defaultModel(data).toString())),
                Path.of(get(v, PREFIX + "reranker-tokenizer-path", OnnxRerankerProvider.defaultTokenizer(data).toString())),
                number(get(v, PREFIX + "rerank-timeout-seconds", "30"), 1, 120), number(get(v, PREFIX + "threads", "4"), 1, 16));
    }
    private static String choice(String value, String... allowed) {
        if (!List.of(allowed).contains(value)) throw new IllegalArgumentException("ML 기능 값 오류");
        return value;
    }
    private static int number(String value, int min, int max) {
        int n = Integer.parseInt(value);
        if (n < min || n > max) throw new IllegalArgumentException("ML 설정 범위 오류");
        return n;
    }
    /** 토큰 해시는 출력하지 않는다. */
    @Override public String toString() { return "MlConfig[port=" + port + ", embedding=" + embedding + ", reranker=" + reranker + ", ocr=" + ocr + "]"; }
}
