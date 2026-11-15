package com.bluesounds.nasquire.core;

import com.bluesounds.nasquire.core.layout.LayoutCheck;
import com.bluesounds.nasquire.core.remote.RemoteException;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/**
 * 표·그림 의심 쪽의 Docling 변환. 색인기는 Docling이 연결됐을 때만 {@link #check}로 판단 객체를 받아 추출기에 넘기고, 추출 뒤 의심 쪽만
 * {@link #refine}으로 쪽 범위(page_range) 한 쪽씩 Docling에 보낸다. 연결되지 않았으면 check가 null이라 판단·전송 비용이 없다.
 * <p>결과 반영: 변환된 Markdown이 1차 결과의 글자(공백 제외) 수의 절반 이상이면 그 쪽의 1차 결과를 Docling 결과로 <b>교체</b>하고,
 * 그보다 짧으면 1차 결과를 남긴 채 Docling 결과를 <b>보강</b>으로 덧붙인다(글자 누락 방지). 근거는 {@code location.type=docling}과 쪽 번호다.
 * 그림 자리표시(&lt;!-- image --&gt;)는 지운다.
 * <p>실패: 혼잡·일시 실패(연결·시간 초과·5xx·429·서킷 열림/동시 변환 중)는 그 파일의 남은 쪽을 보내지 않고 1차 결과로 색인한 뒤 재시도를 요청한다.
 * 그 밖의 실패(4xx·잘못된 응답·크기 초과)는 1차 결과를 유지하고 재시도하지 않는다. 대상은 PDF와 단일 프레임 이미지이며 HWP/HWPX·Office는
 * 원본 표 구조를 쓰므로 대상이 아니다.
 */
public final class DoclingPages {
    /** 추출 결과 한 조각(쪽 번호·근거·글자). */
    public record Part(int page, Location location, String text) { }
    /**
     * 반영한 조각과 재시도 필요 여부. pages는 이번 호출에서 판단한 쪽별 결과(converted·supplemented·failed·retry·skipped)이며
     * 상태·응답 메타데이터용이다(색인기는 쓰지 않는다).
     */
    public record Outcome(List<Part> parts, boolean retry, SortedMap<Integer, String> pages) {
        public Outcome { pages = Collections.unmodifiableSortedMap(new TreeMap<>(pages)); }
        public Outcome(List<Part> parts, boolean retry) { this(parts, retry, new TreeMap<>()); }
    }
    /** 최근 판단(소스 상대 경로·쪽·근거·결과: converted·supplemented·kept·failed·retry·skipped). 메모리에만 둔다. */
    public record Decision(String path, int page, String reason, String result) { }
    /** 시작 뒤 누적 쪽 수와 현재 재시도 대기 파일 수. */
    public record Stats(long converted, long failed, long skipped, int retryWaiting, List<Decision> recent) { }
    private static final Set<String> TYPES = Set.of("pdf", "png", "jpg", "jpeg", "tif", "tiff", "bmp");
    private static final int RECENT = 10;
    private final Supplier<Optional<RemoteDoclingClient>> client;
    private final Supplier<LayoutCheck.Thresholds> thresholds;
    private final IntSupplier maxRetries;
    private final long retryDelayMillis;
    private final AtomicLong converted = new AtomicLong(), failed = new AtomicLong(), skipped = new AtomicLong();
    private final Map<String, Integer> waiting = new java.util.concurrent.ConcurrentHashMap<>();
    private final ArrayDeque<Decision> recent = new ArrayDeque<>();

    /**
     * client는 현재 연결된 Docling(꺼짐·비활성이면 비어 있음), thresholds는 판단 기준값, maxRetries는 파일당 재시도 상한(0~10),
     * retryDelayMillis는 첫 재시도 대기(이후 두 배씩, 실제 재시도는 그 뒤 첫 주기 검사)다.
     */
    public DoclingPages(Supplier<Optional<RemoteDoclingClient>> client, Supplier<LayoutCheck.Thresholds> thresholds, IntSupplier maxRetries, long retryDelayMillis) {
        if (retryDelayMillis < 0) throw new IllegalArgumentException("재시도 대기 오류");
        this.client = Objects.requireNonNull(client); this.thresholds = Objects.requireNonNull(thresholds);
        this.maxRetries = Objects.requireNonNull(maxRetries); this.retryDelayMillis = retryDelayMillis;
    }
    public boolean connected() { return client.get().isPresent(); }
    /** Docling 대상 형식(PDF·이미지)인지. 원래 이름의 확장자로 정한다. */
    public static boolean eligible(Path name) {
        String lower = name.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot >= 0 && TYPES.contains(lower.substring(dot + 1));
    }
    /** 판단 객체. 연결되지 않았거나 대상 형식이 아니면 null(추출기는 판단하지 않는다). */
    public LayoutCheck check(Path name, boolean always) {
        if (!eligible(name) || !connected()) return null;
        return new LayoutCheck(thresholds.get(), always);
    }
    public int maxRetries() { return Math.max(0, Math.min(10, maxRetries.getAsInt())); }
    /** n번째(1부터) 재시도까지의 대기. */
    public long retryDelay(int attempt) { return retryDelayMillis << Math.min(10, Math.max(0, attempt - 1)); }

    /**
     * 의심 쪽을 Docling으로 변환해 반영한다. label은 상태용 소스 상대 경로, name은 형식 판별용 원래 이름, content는 고정한 fd 경로다.
     * 반영 뒤 글자 합계가 maxCharacters를 넘는 쪽은 1차 결과를 유지한다.
     */
    public Outcome refine(String label, Path name, Path content, LayoutCheck check, List<Part> parts, int maxCharacters) throws IOException {
        return refine(label, name, content, check, parts, maxCharacters, null);
    }
    /**
     * 마감 시각(System.nanoTime 기준)이 있는 변환(ML 문서 처리). 쪽마다 시간 상한을 새로 시작하지 않고 남은 시간만 Docling 호출에 넘긴다.
     * 마감이 지나면 남은 쪽은 보내지 않고 일시 실패(retry)로 두며 1차 결과를 유지한다.
     */
    public Outcome refine(String label, Path name, Path content, LayoutCheck check, List<Part> parts, int maxCharacters, long deadlineNanos) throws IOException {
        return refine(label, name, content, check, parts, maxCharacters, Long.valueOf(deadlineNanos));
    }
    private Outcome refine(String label, Path name, Path content, LayoutCheck check, List<Part> parts, int maxCharacters, Long deadlineNanos) throws IOException {
        var suspects = check.suspects();
        if (suspects.isEmpty()) return new Outcome(parts, false);
        boolean pdf = name.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
        var connected = client.get();
        if (connected.isEmpty() || (!pdf && parts.size() != 1)) return new Outcome(parts, false);
        var docling = connected.get();
        int limit = check.thresholds().maxPages();
        var targets = new ArrayList<Integer>();
        var results = new TreeMap<Integer, String>();
        BiConsumer<Integer, String> record = (page, result) -> { results.put(page, result); record(label, page, suspects.get(page), result); };
        for (var e : suspects.entrySet()) {
            if (targets.size() < limit) targets.add(e.getKey());
            else { skipped.incrementAndGet(); record.accept(e.getKey(), "skipped"); }
        }
        long size = Files.size(content);
        if (size < 1 || size > docling.maxFileBytes()) {
            for (int page : targets) { skipped.incrementAndGet(); record.accept(page, "skipped"); }
            return new Outcome(parts, false, results);
        }
        byte[] bytes = Files.readAllBytes(content);
        if (bytes.length != size) throw new IOException("추출 중 파일 변경");
        var next = new ArrayList<>(parts);
        long total = 0;
        for (var p : parts) total += p.text().length();
        boolean retry = false;
        for (int i = 0; i < targets.size(); i++) {
            int page = targets.get(i);
            int index = indexOf(next, page);
            if (index < 0) continue;
            RemoteDoclingClient.Result result;
            var range = pdf ? RemoteDoclingClient.PageRange.single(page) : null;
            try {
                result = deadlineNanos == null ? docling.convert(bytes, name.getFileName().toString(), range, false)
                        : docling.convert(bytes, name.getFileName().toString(), range, false, deadlineNanos);
            }
            catch (RemoteException e) {
                boolean transient_ = e.retryable() || e.kind() == RemoteException.Kind.UNAVAILABLE;
                retry |= transient_;
                // 혼잡·실패한 서버에 같은 파일의 남은 쪽을 계속 보내지 않는다. 남은 쪽은 1차 결과를 유지한다.
                for (int j = i; j < targets.size(); j++) { failed.incrementAndGet(); record.accept(targets.get(j), transient_ ? "retry" : "failed"); }
                break;
            } catch (IllegalArgumentException e) {
                for (int j = i; j < targets.size(); j++) { skipped.incrementAndGet(); record.accept(targets.get(j), "skipped"); }
                break;
            }
            String markdown = clean(result.markdown());
            if (!result.converted() || markdown.isEmpty()) { failed.incrementAndGet(); record.accept(page, "failed"); continue; }
            var original = next.get(index);
            var location = new Location("docling", page, null, null, null, null, null, null);
            boolean supplement = visible(markdown) * 2 < visible(original.text());
            long after = total + markdown.length() - (supplement ? 0 : original.text().length());
            if (after > maxCharacters) { skipped.incrementAndGet(); record.accept(page, "skipped"); continue; }
            total = after;
            if (supplement) next.add(index + 1, new Part(page, location, markdown));
            else next.set(index, new Part(page, location, markdown));
            converted.incrementAndGet(); record.accept(page, supplement ? "supplemented" : "converted");
        }
        return new Outcome(next, retry, results);
    }
    private static int indexOf(List<Part> parts, int page) {
        for (int i = 0; i < parts.size(); i++) if (parts.get(i).page() == page) return i;
        return -1;
    }
    /** 그림 자리표시 줄을 지우고 앞뒤 공백을 정리한다. */
    static String clean(String markdown) {
        if (markdown == null) return "";
        return markdown.replace("<!-- image -->", "").replaceAll("\\n{3,}", "\n\n").strip();
    }
    private static long visible(String text) { return text.codePoints().filter(c -> !Character.isWhitespace(c)).count(); }
    private void record(String path, int page, String reason, String result) {
        synchronized (recent) {
            if (recent.size() == RECENT) recent.removeFirst();
            recent.addLast(new Decision(path, page, reason, result));
        }
    }
    /** 색인기가 소스 검사 뒤 재시도 대기 파일 수를 알린다(0이면 지운다). */
    public void retryWaiting(String source, int files) { if (files <= 0) waiting.remove(source); else waiting.put(source, files); }
    public Stats stats() {
        List<Decision> list; synchronized (recent) { list = List.copyOf(recent); }
        return new Stats(converted.get(), failed.get(), skipped.get(), waiting.values().stream().mapToInt(Integer::intValue).sum(), list);
    }
}
