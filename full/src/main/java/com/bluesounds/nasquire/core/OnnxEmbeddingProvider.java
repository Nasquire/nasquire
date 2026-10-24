package com.bluesounds.nasquire.core;

import ai.onnxruntime.*;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import java.nio.file.Path;
import java.util.*;

/** E5: 접두사, attention mask 평균 풀링, L2 정규화. 한 번에 한 조각만 추론한다. */
public final class OnnxEmbeddingProvider implements Providers.EmbeddingProvider {
    private final Path model, tokenizerPath, downloadRoot;
    private final String modelUrl, tokenizerUrl, modelHash, tokenizerHash;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    public OnnxEmbeddingProvider(Path model, Path tokenizerPath, String modelUrl, String tokenizerUrl, String modelHash, String tokenizerHash) {
        this(model, tokenizerPath, modelUrl, tokenizerUrl, modelHash, tokenizerHash, null);
    }
    public OnnxEmbeddingProvider(Path model, Path tokenizerPath, String modelUrl, String tokenizerUrl, String modelHash, String tokenizerHash, Path downloadRoot) {
        this.downloadRoot = downloadRoot;
        this.model = model; this.tokenizerPath = tokenizerPath; this.modelUrl = modelUrl; this.tokenizerUrl = tokenizerUrl;
        this.modelHash = modelHash; this.tokenizerHash = tokenizerHash;
        if (modelHash == null || !modelHash.matches("[a-fA-F0-9]{64}") || tokenizerHash == null || !tokenizerHash.matches("[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("모델·토크나이저 SHA-256 필요");
    }
    public String modelId() { return "multilingual-e5-small:int8:" + modelHash + ":" + tokenizerHash + ":e5-pooling-v1"; }
    public int dimensions() { return 384; }
    private void load() throws Exception {
        if (session != null) return;
        var modelFile = ModelFiles.verified(model, modelUrl, modelHash, downloadRoot);
        var tokenFile = ModelFiles.verified(tokenizerPath, tokenizerUrl, tokenizerHash, downloadRoot);
        try (var options = new OrtSession.SessionOptions()) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1);
            options.setMemoryPatternOptimization(false); options.setCPUArenaAllocator(false);
            var env = OrtEnvironment.getEnvironment(); env.setTelemetry(false);
            var loadedSession = env.createSession(modelFile.toString(), options);
            try {
                tokenizer = HuggingFaceTokenizer.builder().optTokenizerPath(tokenFile).optMaxLength(256).optTruncation(true).optPadding(false).build();
                session = loadedSession;
            } catch (Exception | LinkageError e) { loadedSession.close(); throw e; }
        }
    }
    public float[] embed(String text) { return infer("passage: " + text); }
    public float[] embedQuery(String text) { return infer("query: " + text); }
    private synchronized float[] infer(String text) {
        var inputs = new HashMap<String, OnnxTensor>();
        try {
            load(); var encoded = tokenizer.encode(text);
            var env = OrtEnvironment.getEnvironment();
            inputs.put("input_ids", OnnxTensor.createTensor(env, new long[][] {encoded.getIds()}));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, new long[][] {encoded.getAttentionMask()}));
            if (session.getInputNames().contains("token_type_ids")) inputs.put("token_type_ids", OnnxTensor.createTensor(env, new long[][] {encoded.getTypeIds()}));
            try (var result = session.run(inputs)) {
                float[][] tokens = ((float[][][])result.get("last_hidden_state").orElseGet(() -> result.get(0)).getValue())[0];
                float[] pooled = new float[dimensions()]; long[] mask = encoded.getAttentionMask(); int count = 0;
                for (int i = 0; i < tokens.length; i++) if (mask[i] != 0) {
                    count++; for (int j = 0; j < pooled.length; j++) pooled[j] += tokens[i][j];
                }
                if (count == 0) throw new IllegalStateException("토큰 없음");
                for (int j = 0; j < pooled.length; j++) pooled[j] /= count;
                return RemoteEmbeddingProvider.normalized(pooled);
            }
        } catch (Exception | LinkageError e) { throw new IllegalStateException("내장 임베딩 실패: 모델·해시·네이티브 런타임을 확인하세요"); }
        finally { inputs.values().forEach(OnnxTensor::close); }
    }
    public synchronized void close() {
        if (tokenizer != null) { tokenizer.close(); tokenizer = null; }
        if (session != null) { try { session.close(); } catch (OrtException e) { throw new IllegalStateException("모델 종료 실패"); } finally { session = null; } }
    }
}
