package com.bluesounds.nasquire.core;

import com.bluesounds.nasquire.core.remote.*;
import java.time.Duration;

/**
 * Nasquire ML 서버 호환 원격 OCR(POST {기본 주소}/ocr, 본문=이미지 바이트, Bearer, 응답 {"text": "..."}).
 * 쪽 렌더링·이미지 디코딩은 이 JVM 밖 자식 프로세스({@code ocr.OcrTextExtractor})가 만든 PNG만 보내며 원본 파일·파일 이름은 보내지 않는다.
 * 서버 묶음의 round_robin·페일오버(연결 실패·시간 초과·5xx·429)·서킷 브레이커를 따르고 로컬 OCR로 대체하지 않는다.
 */
public final class RemoteOcrProvider implements Providers.OcrProvider {
    /** Nasquire ML 서버의 /models가 보고하는 OCR 엔진 ID. */
    public static final String DEFAULT_ENGINE = "tesseract";
    /** 응답 상한. 쪽 하나의 인식 결과로 충분하다. */
    static final int MAX_RESPONSE = 4 * 1024 * 1024;
    private final RemotePool pool;
    private final String engine;
    private final Duration timeout;
    /** seconds는 페일오버를 포함한 쪽(이미지) 하나의 전체 시간 상한(1~300초)이다. 공급자를 닫으면 묶음도 닫는다. */
    public RemoteOcrProvider(RemotePool pool, String engine, int seconds) {
        if (pool == null || engine == null || engine.isBlank() || engine.length() > 256 || seconds < 1 || seconds > RemoteServer.MAX_TIMEOUT_SECONDS)
            throw new IllegalArgumentException("OCR 설정 범위 오류");
        this.pool = pool; this.engine = engine; timeout = Duration.ofSeconds(seconds);
    }
    public RemotePool pool() { return pool; }
    /** 언어는 서버 설정(kor+eng)을 따르므로 보내지 않는다. 모든 서버가 실패하면 {@link RemoteException}. */
    public String recognize(byte[] image, String language) {
        return pool.execute(timeout, (http, server, limit) -> text(http, server, limit, image)).value();
    }
    @Override public String recognize(byte[] image, String language, long deadlineNanos) {
        return pool.execute(timeout, deadlineNanos, (http, server, limit) -> text(http, server, limit, image)).value();
    }
    private static String text(RemoteHttp http, RemoteServer server, Duration limit, byte[] image) {
        var value = http.postBytes(server, "/ocr", contentType(image), image, limit, MAX_RESPONSE).getAsJsonObject().get("text");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new RemoteException(RemoteException.Kind.INVALID_RESPONSE);
        return value.getAsString();
    }
    /** PNG·JPEG·TIFF·GIF·BMP 머리말로 형식을 정한다. 모르면 application/octet-stream. */
    static String contentType(byte[] image) {
        if (starts(image, 0x89, 'P', 'N', 'G')) return "image/png";
        if (starts(image, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (starts(image, 'I', 'I', 42, 0) || starts(image, 'M', 'M', 0, 42)) return "image/tiff";
        if (starts(image, 'G', 'I', 'F', '8')) return "image/gif";
        if (starts(image, 'B', 'M')) return "image/bmp";
        return "application/octet-stream";
    }
    private static boolean starts(byte[] data, int... prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((data[i] & 0xFF) != prefix[i]) return false;
        return true;
    }
    /** 서버 하나의 엔진 목록(지원 시)을 확인하고 작은 흰 PNG 1건으로 응답 형식을 확인한다. */
    public RemoteCheck verify(String server, Duration limit) {
        try {
            var models = pool.probe(server, limit, (http, s, t) -> http.models(s, t));
            if (models.isPresent() && !models.get().contains(engine)) return new RemoteCheck(server, "mismatch", "engine");
            pool.probe(server, limit, (http, s, t) -> text(http, s, t, SAMPLE));
            return new RemoteCheck(server, "ok", "");
        } catch (RemoteException e) { return RemoteCheck.failed(server, e); }
    }
    /** 연결 시험용 16×16 흰 PNG. */
    static final byte[] SAMPLE = sample();
    private static byte[] sample() {
        var image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        var g = image.createGraphics(); g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, 16, 16); g.dispose();
        var out = new java.io.ByteArrayOutputStream();
        try { javax.imageio.ImageIO.write(image, "png", out); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        return out.toByteArray();
    }
    public void close() { pool.close(); }
}
