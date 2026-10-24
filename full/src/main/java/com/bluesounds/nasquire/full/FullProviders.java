package com.bluesounds.nasquire.full;

import com.bluesounds.nasquire.core.*;
import java.nio.file.Path;
import java.util.Properties;

/** 구현 모듈이 런타임 클래스 경로에 있을 때만 발견된다. */
public final class FullProviders implements Providers.FullProvider {
    public Providers.EmbeddingProvider embedding(Properties p, Path data) {
        return new OnnxEmbeddingProvider(Path.of(p.getProperty("modelPath")), Path.of(p.getProperty("tokenizerPath")),
                Boolean.parseBoolean(p.getProperty("download")) ? p.getProperty("modelUrl") : "",
                Boolean.parseBoolean(p.getProperty("download")) ? p.getProperty("tokenizerUrl") : "",
                p.getProperty("modelSha256"), p.getProperty("tokenizerSha256"), data);
    }
    public Providers.RerankerProvider reranker(String url, String model, String key, int topN, int seconds) {
        return new RemoteRerankerProvider(url, model, key, topN, seconds);
    }
    public Providers.RerankerProvider localReranker(Path model, Path tokenizer, boolean download, Path data, int topN, int seconds) {
        return new OnnxRerankerProvider(model, tokenizer, download, data, topN, seconds);
    }
    public AutoCloseable ml(String[] args, java.util.Map<String, String> environment) throws Exception {
        return MlServer.start(MlConfig.load(args, environment));
    }
    public TextExtractor ocr(Path data, int seconds, int memory, int pages) {
        return new OcrTextExtractor(new TesseractOcrProvider(data, seconds, memory), pages);
    }
}
