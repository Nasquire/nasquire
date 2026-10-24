package com.bluesounds.nasquire.full;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import com.bluesounds.nasquire.core.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * 로컬 ONNX cross-encoder 리랭커. 고정 리비전·SHA-256 모델만 읽고, 다운로드는 데이터 폴더 안에서만 허용한다.
 * 한 번에 한 쌍씩 추론하며 혼잡·시간 초과·실패 때는 입력 순서를 유지한다.
 */
public final class OnnxRerankerProvider implements Providers.RerankerProvider {
    /** cross-encoder/mmarco-mMiniLMv2-L12-H384-v1 (Apache-2.0), 고정 커밋. qint8 파일은 amd64/arm64 공용이다. */
    public static final String NAME = "mmarco-mMiniLMv2-L12-H384-v1";
    static final String REVISION = "1427fd652930e4ba29e8149678df786c240d8825";
    static final String BASE = "https://huggingface.co/cross-encoder/mmarco-mMiniLMv2-L12-H384-v1/resolve/" + REVISION + "/";
    public static final String MODEL_URL = BASE + "onnx/model_qint8_arm64.onnx", TOKENIZER_URL = BASE + "tokenizer.json";
    public static final String MODEL_SHA256 = "1825907d6c1a9001ff78124780bbde20a614a8c3df3b63409cf3c72c6fe5c8b4";
    public static final String TOKENIZER_SHA256 = "62c24cdc13d4c9952d63718d6c9fa4c287974249e16b7ade6d5a85e7bbb75626";
    static final int MAX_TOKENS = 256, MAX_DOCUMENTS = 100, MAX_CHARACTERS = 4096;
    private final Path model, tokenizerPath, downloadRoot;
    private final boolean download;
    private final int topN, seconds;
    private final Semaphore active = new Semaphore(1);
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    public OnnxRerankerProvider(Path model, Path tokenizerPath, boolean download, Path downloadRoot, int topN, int seconds) {
        if (model == null || tokenizerPath == null || topN < 1 || topN > MAX_DOCUMENTS || seconds < 1 || seconds > 120) throw new IllegalArgumentException("로컬 리랭커 설정 오류");
        this.model = model; this.tokenizerPath = tokenizerPath; this.download = download; this.downloadRoot = downloadRoot;
        this.topN = topN; this.seconds = seconds;
    }
    public static Path defaultModel(Path data) { return data.resolve("models").resolve(NAME).resolve("model-qint8.onnx"); }
    public static Path defaultTokenizer(Path data) { return data.resolve("models").resolve(NAME).resolve("tokenizer.json"); }

    private void load() throws Exception {
        if (session != null) return;
        var modelFile = ModelFiles.verified(model, download ? MODEL_URL : "", MODEL_SHA256, downloadRoot);
        var tokenFile = ModelFiles.verified(tokenizerPath, download ? TOKENIZER_URL : "", TOKENIZER_SHA256, downloadRoot);
        try (var options = new OrtSession.SessionOptions()) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1);
            options.setMemoryPatternOptimization(false); options.setCPUArenaAllocator(false);
            var env = OrtEnvironment.getEnvironment(); env.setTelemetry(false);
            var loaded = env.createSession(modelFile.toString(), options);
            try {
                tokenizer = HuggingFaceTokenizer.builder().optTokenizerPath(tokenFile).optMaxLength(MAX_TOKENS)
                        .optTruncation(true).optPadding(false).build();
                session = loaded;
            } catch (Exception | LinkageError e) { loaded.close(); throw e; }
        }
    }

    /** 문서별 관련도 점수(logit). 시간 상한을 넘기면 예외. 호출자는 동시 호출을 제한한다. */
    public synchronized float[] scores(String query, List<String> documents, long deadlineNanos) {
        if (query == null || query.isBlank() || query.length() > MAX_CHARACTERS || documents.size() > MAX_DOCUMENTS
                || documents.stream().anyMatch(d -> d == null || d.length() > MAX_CHARACTERS)) throw new IllegalArgumentException("리랭크 입력 오류");
        var result = new float[documents.size()];
        try {
            load();
            var env = OrtEnvironment.getEnvironment();
            for (int i = 0; i < documents.size(); i++) {
                if (System.nanoTime() > deadlineNanos) throw new IllegalStateException("리랭크 시간 상한 초과");
                var encoded = tokenizer.encode(query, documents.get(i));
                var inputs = new HashMap<String, OnnxTensor>();
                try {
                    inputs.put("input_ids", OnnxTensor.createTensor(env, new long[][] {encoded.getIds()}));
                    inputs.put("attention_mask", OnnxTensor.createTensor(env, new long[][] {encoded.getAttentionMask()}));
                    if (session.getInputNames().contains("token_type_ids")) inputs.put("token_type_ids", OnnxTensor.createTensor(env, new long[][] {encoded.getTypeIds()}));
                    try (var output = session.run(inputs)) {
                        float score = ((float[][])output.get(0).getValue())[0][0];
                        if (!Float.isFinite(score)) throw new IllegalStateException("점수 오류");
                        result[i] = score;
                    }
                } finally { inputs.values().forEach(OnnxTensor::close); }
            }
            return result;
        } catch (IllegalStateException | IllegalArgumentException e) { throw e; }
        catch (Exception | LinkageError e) { throw new IllegalStateException("로컬 리랭커 실패: 모델·해시·네이티브 런타임을 확인하세요"); }
    }
    public long deadline() { return System.nanoTime() + seconds * 1_000_000_000L; }

    public List<SearchIndex.Hit> rerank(String query, List<SearchIndex.Hit> hits) {
        if (hits.size() < 2 || !active.tryAcquire()) return hits;
        try {
            int count = Math.min(topN, hits.size());
            var docs = hits.subList(0, count).stream().map(h -> h.text().substring(0, Math.min(1200, h.text().length()))).toList();
            float[] scores = scores(query.length() > MAX_CHARACTERS ? query.substring(0, MAX_CHARACTERS) : query, docs, deadline());
            var order = new ArrayList<Integer>(); for (int i = 0; i < count; i++) order.add(i);
            order.sort(Comparator.<Integer>comparingDouble(i -> scores[i]).reversed());
            var ranked = new ArrayList<SearchIndex.Hit>(); order.forEach(i -> ranked.add(hits.get(i)));
            ranked.addAll(hits.subList(count, hits.size())); return List.copyOf(ranked);
        } catch (RuntimeException e) { return hits; }
        finally { active.release(); }
    }
    public synchronized void close() {
        if (tokenizer != null) { tokenizer.close(); tokenizer = null; }
        if (session != null) { try { session.close(); } catch (OrtException e) { throw new IllegalStateException("모델 종료 실패"); } finally { session = null; } }
    }
}
