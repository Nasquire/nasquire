package com.bluesounds.nasquire.core.remote;

import com.google.gson.*;
import java.io.IOException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/**
 * JSON 응답 요청 1회. 리디렉트를 따르지 않고 응답 크기·전체 시간에 상한을 둔다. 실패는 {@link RemoteException}으로 분류한다.
 * 서버 키는 기본 Authorization: Bearer로, docling-serve처럼 다른 헤더를 쓰는 서버는 {@link Auth#X_API_KEY}로 보낸다.
 */
public final class RemoteHttp implements AutoCloseable {
    /** 서버 키를 보내는 방식. 키가 비어 있으면 인증 헤더를 보내지 않는다. */
    public enum Auth { BEARER, X_API_KEY }
    /** HTTP/1.1 고정: 평문 http에서 JDK 기본 h2c 업그레이드 헤더를 본문 요청에 붙이면 일부 서버(uvicorn 등)가 422·400으로 거부한다. */
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    public JsonElement post(RemoteServer server, String path, JsonElement body, Duration timeout, int maxBytes) {
        return send(server, HttpRequest.newBuilder(server.resolve(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())), Auth.BEARER, timeout, maxBytes);
    }
    /** 본문 바이트를 그대로 보낸다(예: OCR 이미지). */
    public JsonElement postBytes(RemoteServer server, String path, String contentType, byte[] body, Duration timeout, int maxBytes) {
        return send(server, HttpRequest.newBuilder(server.resolve(path)).header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)), Auth.BEARER, timeout, maxBytes);
    }
    /** multipart/form-data 요청. */
    public JsonElement postMultipart(RemoteServer server, String path, Multipart body, Auth auth, Duration timeout, int maxBytes) {
        return send(server, HttpRequest.newBuilder(server.resolve(path)).header("Content-Type", body.contentType())
                .POST(body.publisher()), auth, timeout, maxBytes);
    }
    public JsonElement get(RemoteServer server, String path, Duration timeout, int maxBytes) {
        return get(server, path, Auth.BEARER, timeout, maxBytes);
    }
    public JsonElement get(RemoteServer server, String path, Auth auth, Duration timeout, int maxBytes) {
        return send(server, HttpRequest.newBuilder(server.resolve(path)).GET(), auth, timeout, maxBytes);
    }
    /**
     * 메모리에서 만드는 multipart/form-data 본문. 필드 이름·값·파일 이름에 따옴표·줄바꿈을 허용하지 않는다.
     * 같은 이름을 여러 번 넣으면 배열 필드(예: page_range, to_formats)가 된다.
     */
    public static final class Multipart {
        private final String boundary = "nasquire-" + java.util.UUID.randomUUID();
        // 파일 내용은 복사하지 않고 그대로 이어 보낸다(큰 문서의 힙 사용을 줄인다).
        private final List<byte[]> parts = new java.util.ArrayList<>();
        public Multipart field(String name, String value) {
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + safe(name) + "\"\r\n\r\n" + safe(value) + "\r\n");
            return this;
        }
        public Multipart file(String name, String fileName, String contentType, byte[] content) {
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + safe(name) + "\"; filename=\"" + safe(fileName)
                    + "\"\r\nContent-Type: " + safe(contentType) + "\r\n\r\n");
            parts.add(content); write("\r\n");
            return this;
        }
        public String contentType() { return "multipart/form-data; boundary=" + boundary; }
        HttpRequest.BodyPublisher publisher() {
            var all = new java.util.ArrayList<HttpRequest.BodyPublisher>();
            for (byte[] part : parts) all.add(HttpRequest.BodyPublishers.ofByteArray(part));
            all.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n"));
            return HttpRequest.BodyPublishers.concat(all.toArray(HttpRequest.BodyPublisher[]::new));
        }
        private void write(String text) { parts.add(text.getBytes(StandardCharsets.UTF_8)); }
        private static String safe(String value) {
            if (value == null || value.chars().anyMatch(c -> c == '"' || c == '\\' || c < 0x20 || c == 0x7f)) throw new IllegalArgumentException("multipart 값 오류");
            return value;
        }
    }
    /**
     * OpenAI 호환 GET /models의 모델 ID 목록(data[].id). 경로가 없거나(4xx) 형식이 다르면 빈 값으로 확인을 건너뛴다.
     * 연결 실패·시간 초과·5xx·429는 그대로 던진다.
     */
    public java.util.Optional<java.util.Set<String>> models(RemoteServer server, Duration timeout) {
        try {
            var data = get(server, "/models", timeout, 256 * 1024).getAsJsonObject().getAsJsonArray("data");
            var ids = new java.util.HashSet<String>();
            for (var entry : data) ids.add(entry.getAsJsonObject().get("id").getAsString());
            return ids.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(ids);
        } catch (RemoteException e) {
            if (e.retryable() || e.kind() == RemoteException.Kind.UNAVAILABLE) throw e;
            return java.util.Optional.empty();
        } catch (RuntimeException e) { return java.util.Optional.empty(); }
    }
    private JsonElement send(RemoteServer server, HttpRequest.Builder request, Auth auth, Duration timeout, int maxBytes) {
        if (timeout.isNegative() || timeout.isZero()) throw new RemoteException(RemoteException.Kind.TIMEOUT);
        request.timeout(timeout).header("Accept", "application/json");
        if (!server.key().isEmpty()) {
            if (auth == Auth.X_API_KEY) request.header("X-Api-Key", server.key());
            else request.header("Authorization", "Bearer " + server.key());
        }
        // 재시도 대상이 아닌 4xx·3xx는 상태 줄만 보고 본문을 기다리지 않고 버린다(본문을 지연하는 서버가 시간 상한까지 붙잡지 못하게).
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request.build(),
                info -> clientError(info.statusCode()) ? discarded() : bounded(maxBytes));
        HttpResponse<byte[]> response;
        try {
            // 본문을 끝없이 지연하는 서버에도 요청 전체 시간 상한을 적용한다.
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) { throw new RemoteException(RemoteException.Kind.TIMEOUT); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RemoteException(RemoteException.Kind.UNAVAILABLE); }
        catch (ExecutionException e) { throw classify(e.getCause()); }
        finally { if (!pending.isDone()) pending.cancel(true); }
        int status = response.statusCode();
        if (status == 429) throw new RemoteException(RemoteException.Kind.RATE_LIMITED, status);
        if (status >= 500) throw new RemoteException(RemoteException.Kind.SERVER_ERROR, status);
        if (clientError(status)) throw new RemoteException(RemoteException.Kind.CLIENT_ERROR, status);
        try { return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)); }
        catch (RuntimeException e) { throw new RemoteException(RemoteException.Kind.INVALID_RESPONSE); }
    }
    private static RemoteException classify(Throwable cause) {
        for (Throwable c = cause; c != null; c = c.getCause()) {
            if (c instanceof TooLarge) return new RemoteException(RemoteException.Kind.INVALID_RESPONSE);
            if (c instanceof HttpTimeoutException) return new RemoteException(RemoteException.Kind.TIMEOUT);
        }
        return new RemoteException(RemoteException.Kind.CONNECT);
    }
    /** 즉시 실패하는 상태(2xx·429·5xx가 아닌 것). */
    private static boolean clientError(int status) { return status != 429 && status < 500 && (status < 200 || status > 299); }
    /** 본문을 구독하자마자 취소하고(연결을 닫는다) 바로 완료한다. */
    private static HttpResponse.BodySubscriber<byte[]> discarded() {
        return new HttpResponse.BodySubscriber<>() {
            public CompletionStage<byte[]> getBody() { return CompletableFuture.completedFuture(null); }
            public void onSubscribe(Flow.Subscription subscription) { subscription.cancel(); }
            public void onNext(List<ByteBuffer> buffers) { }
            public void onError(Throwable error) { }
            public void onComplete() { }
        };
    }
    private static final class TooLarge extends IOException { TooLarge() { super("응답 상한 초과"); } }
    private static HttpResponse.BodySubscriber<byte[]> bounded(int maxBytes) {
        return new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
            private Flow.Subscription subscription;
            private long size;
            public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
            public void onNext(List<ByteBuffer> buffers) {
                for (var b : buffers) size += b.remaining();
                if (size > maxBytes) { subscription.cancel(); delegate.onError(new TooLarge()); }
                else delegate.onNext(buffers);
            }
            public void onError(Throwable error) { delegate.onError(error); }
            public void onComplete() { delegate.onComplete(); }
        };
    }
    public void close() { client.shutdownNow(); }
}
