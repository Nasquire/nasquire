package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.RemoteUrl;
import com.bluesounds.nasquire.core.RemoteDoclingClient;
import com.bluesounds.nasquire.core.remote.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * ML 전용 모드 설정. 우선순위는 명령행 > 환경변수 > 설정 파일이다.
 * 인증 토큰·원격 공급자 키는 명령행에서 받지 않는다(프로세스 목록 노출 방지). 설정 파일·환경변수·파일만 허용한다.
 * 원격 기능(embedding·reranker·ocr·docling)은 {@code nasquire.ml.<기능>-servers=이름,...}과
 * {@code nasquire.ml.<기능>-server.<이름>.url|key|key-file|timeout-seconds}로 서버 묶음을 받는다.
 */
public record MlConfig(String address, int port, Path data, byte[] tokenHash, String embedding, String embeddingModel,
        String reranker, String rerankerModel, String ocr, int ocrTimeoutSeconds, int ocrMemoryMiB, boolean download,
        Path embeddingModelPath, Path embeddingTokenizerPath, Path rerankerModelPath, Path rerankerTokenizerPath,
        int rerankTimeoutSeconds, int threads, String docling, Remote embeddingRemote, Remote rerankerRemote,
        Remote ocrRemote, Remote doclingRemote, int ocrMaxPages, int doclingMaxPages, int documentTimeoutSeconds) {
    /** 문서 처리(/v1/ocr·/v1/extract·/process) 요청 하나의 전체 시간 상한 기본값·최댓값(초). */
    static final int DEFAULT_DOCUMENT_TIMEOUT_SECONDS = 120, MAX_DOCUMENT_TIMEOUT_SECONDS = 600;
    /**
     * 원격 기능 하나의 서버 묶음(같은 모델·차원/엔진). model은 임베딩·리랭커 모델 또는 OCR 엔진 이름(Docling은 빈 값),
     * dimensions는 임베딩만(그 밖에는 0), timeoutSeconds는 페일오버를 포함한 요청 전체 상한이다. 주소·키는 출력하지 않는다.
     */
    public record Remote(List<RemoteServer> servers, String model, int dimensions, int timeoutSeconds) {
        public Remote { servers = List.copyOf(servers); }
        @Override public String toString() { return "remote(" + servers.size() + ")"; }
    }
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
            String dotted = key.toLowerCase(Locale.ROOT).replace('-', '.');
            if (dotted.startsWith(PREFIX) && (dotted.equals(PREFIX + "token") || dotted.endsWith(".key")))
                throw new IllegalArgumentException("원격 공급자 키는 명령행으로 받지 않습니다. 설정 파일·환경변수·키 파일(key-file)을 사용하세요");
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
        String embedding = choice(get(v, PREFIX + "embedding", "off"), "off", "builtin", "remote");
        String reranker = choice(get(v, PREFIX + "reranker", "off"), "off", "builtin", "remote");
        String ocr = choice(get(v, PREFIX + "ocr", "off"), "off", "tesseract", "remote");
        String docling = choice(get(v, PREFIX + "docling", "off"), "off", "remote");
        if (embedding.equals("off") && reranker.equals("off") && ocr.equals("off") && docling.equals("off"))
            throw new IllegalArgumentException("ML 모드에서 임베딩·리랭커·OCR·Docling 중 하나 이상을 켜야 합니다");
        Path models = data.resolve("models");
        boolean remoteOcr = ocr.equals("remote");
        int rerankSeconds = number(get(v, PREFIX + "rerank-timeout-seconds", "30"), 1, 120);
        int ocrSeconds = number(get(v, PREFIX + "ocr-timeout-seconds", remoteOcr ? "60" : "15"), 1, remoteOcr ? RemoteServer.MAX_TIMEOUT_SECONDS : 60);
        String embeddingModel = embedding.equals("remote") ? required(v, PREFIX + "embedding-model") : get(v, PREFIX + "embedding-model", "multilingual-e5-small");
        String rerankerModel = reranker.equals("remote") ? required(v, PREFIX + "reranker-model") : get(v, PREFIX + "reranker-model", OnnxRerankerProvider.NAME);
        Remote embeddingRemote = null, rerankerRemote = null, ocrRemote = null, doclingRemote = null;
        if (embedding.equals("remote")) {
            int seconds = number(get(v, PREFIX + "embedding-timeout-seconds", "30"), 1, RemoteServer.MAX_TIMEOUT_SECONDS);
            embeddingRemote = new Remote(servers(v, "embedding", seconds), model(embeddingModel),
                    number(required(v, PREFIX + "embedding-dimensions"), 1, MlGateway.MAX_DIMENSIONS), seconds);
        }
        if (reranker.equals("remote")) rerankerRemote = new Remote(servers(v, "reranker", rerankSeconds), model(rerankerModel), 0, rerankSeconds);
        if (remoteOcr) ocrRemote = new Remote(servers(v, "ocr", ocrSeconds), model(get(v, PREFIX + "ocr-engine", "tesseract")), 0, ocrSeconds);
        if (docling.equals("remote")) {
            int seconds = number(get(v, PREFIX + "docling-timeout-seconds", Integer.toString(RemoteDoclingClient.DEFAULT_TIMEOUT_SECONDS)),
                    1, RemoteDoclingClient.MAX_TIMEOUT_SECONDS);
            doclingRemote = new Remote(servers(v, "docling", seconds), "", 0, seconds);
        }
        return new MlConfig(get(v, "server.address", "127.0.0.1"), number(get(v, "server.port", "8080"), 0, 65535), data,
                MlServer.sha256(token), embedding, embeddingModel, reranker, rerankerModel, ocr,
                ocrSeconds, number(get(v, PREFIX + "ocr-memory-mib", "512"), 128, 2048),
                Boolean.parseBoolean(get(v, PREFIX + "download", "false")),
                Path.of(get(v, PREFIX + "embedding-model-path", models.resolve("multilingual-e5-small/model-int8.onnx").toString())),
                Path.of(get(v, PREFIX + "embedding-tokenizer-path", models.resolve("multilingual-e5-small/tokenizer.json").toString())),
                Path.of(get(v, PREFIX + "reranker-model-path", OnnxRerankerProvider.defaultModel(data).toString())),
                Path.of(get(v, PREFIX + "reranker-tokenizer-path", OnnxRerankerProvider.defaultTokenizer(data).toString())),
                rerankSeconds, number(get(v, PREFIX + "threads", "4"), 1, 16),
                docling, embeddingRemote, rerankerRemote, ocrRemote, doclingRemote,
                // 문서 하나에서 OCR할 최대 쪽 수, Docling으로 보낼 최대 쪽 수(쪽마다 요청 1건).
                number(get(v, PREFIX + "ocr-max-pages", "20"), 1, 100),
                number(get(v, PREFIX + "docling-max-pages", "10"), 1, 100),
                // 문서 하나의 추출·OCR·Docling 전체 마감(초). 단계마다 남은 시간만 쓴다.
                number(get(v, PREFIX + "document-timeout-seconds", Integer.toString(DEFAULT_DOCUMENT_TIMEOUT_SECONDS)), 1, MAX_DOCUMENT_TIMEOUT_SECONDS));
    }
    private static String required(Map<String, String> v, String key) {
        String value = get(v, key, null);
        if (value == null) throw new IllegalArgumentException("원격 기능 설정 필요: " + key);
        return value;
    }
    private static String model(String value) {
        if (value.length() > 256 || value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) throw new IllegalArgumentException("원격 모델 이름 오류");
        return value;
    }
    /**
     * 기능 하나의 서버 목록. 서버 이름은 상태·응답 헤더에 쓰는 공개 식별자다. 환경변수로 줄 때는 이름을 영문 소문자·숫자로 한다
     * (예: NASQUIRE_ML_EMBEDDING_SERVER_GPU1_URL). 키는 key(설정 파일·환경변수) 또는 key-file 중 하나이며 없으면 인증 헤더를 보내지 않는다.
     * 루프백이 아닌 평문 http는 기능별 allow-insecure-http=true일 때만 허용한다. 오류 메시지에는 주소·키를 넣지 않는다.
     */
    static List<RemoteServer> servers(Map<String, String> v, String feature, int totalSeconds) throws IOException {
        String list = get(v, PREFIX + feature + "-servers", null);
        if (list == null) throw new IllegalArgumentException("원격 서버 목록 필요: " + PREFIX + feature + "-servers");
        boolean insecure = Boolean.parseBoolean(get(v, PREFIX + feature + "-allow-insecure-http", "false"));
        var result = new ArrayList<RemoteServer>(); var names = new HashSet<String>();
        for (String raw : list.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) continue;
            if (!RemoteServer.validName(name) || !names.add(name.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("원격 서버 이름 오류·중복 (" + feature + ")");
            String base = PREFIX + feature + "-server." + name + ".", where = " (" + feature + "/" + name + ")";
            String url = get(v, base + "url", null);
            if (url == null) throw new IllegalArgumentException("원격 서버 주소 필요" + where);
            String key = get(v, base + "key", null), keyFile = get(v, base + "key-file", null);
            if (key != null && keyFile != null) throw new IllegalArgumentException("원격 서버 키와 키 파일 중 하나만 설정하세요" + where);
            if (keyFile != null) {
                Path file = Path.of(keyFile);
                if (Files.size(file) > 8192) throw new IllegalArgumentException("원격 서버 키 파일 크기 오류" + where);
                key = Files.readString(file).strip();
            }
            int seconds = number(get(v, base + "timeout-seconds", Integer.toString(Math.min(totalSeconds, RemoteServer.MAX_TIMEOUT_SECONDS))), 1, RemoteServer.MAX_TIMEOUT_SECONDS);
            if (!insecure && RemoteUrl.insecure(url))
                throw new IllegalArgumentException("원격 연결에는 HTTPS가 필요합니다 (평문은 " + PREFIX + feature + "-allow-insecure-http=true로 명시 허용)" + where);
            try { result.add(new RemoteServer(name, url, key, seconds)); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("원격 서버 설정 오류" + where); }
        }
        if (result.isEmpty() || result.size() > RemotePool.MAX_SERVERS)
            throw new IllegalArgumentException("원격 서버는 1~" + RemotePool.MAX_SERVERS + "개 (" + feature + ")");
        return result;
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
    /** 토큰 해시·원격 주소·키는 출력하지 않는다. */
    @Override public String toString() {
        return "MlConfig[port=" + port + ", embedding=" + mode(embedding, embeddingRemote) + ", reranker=" + mode(reranker, rerankerRemote)
                + ", ocr=" + mode(ocr, ocrRemote) + ", docling=" + mode(docling, doclingRemote) + "]";
    }
    private static String mode(String value, Remote remote) { return remote == null ? value : remote.toString(); }
}
