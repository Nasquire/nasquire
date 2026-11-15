package com.bluesounds.nasquire.core.layout;

import java.util.*;

/**
 * 파일 하나의 표·그림 의심 쪽 판단. Docling이 연결됐을 때만 색인기가 만들어 추출기에 넘기고, 연결되지 않으면 null을 넘겨 판단 코드를 돌리지 않는다.
 * 추출기는 쪽마다 신호({@link PageSignals}, 스캔 쪽은 직선 격자 수)를 알리고 이 객체가 기준값으로 판단해 의심 쪽과 짧은 근거를 모은다.
 * always(소스별 "항상 Docling")이면 신호를 재지 않고 모든 쪽을 대상으로 한다. 판단만 하며 원격 호출은 하지 않는다.
 */
public final class LayoutCheck {
    /**
     * 기준값. minGridLines는 가로·세로 직선(벡터 선·사각형 변, 스캔 래스터 직선) 각각의 최소 개수, minColumns·minRows는 큰 간격으로 나뉜
     * 글자 열의 최소 수와 열마다 같은 x에 정렬된 최소 줄 수, minImagePercent는 쪽 면적 대비 이미지 비율(0이면 끔), maxPages는 파일 하나에서
     * Docling으로 보낼 최대 쪽 수다. 기본값은 일반 글·2단 편집·머리말 선 하나 정도로는 의심하지 않도록 보수적으로 잡았다.
     */
    public record Thresholds(int minGridLines, int minColumns, int minRows, int minImagePercent, int maxPages) {
        public static final Thresholds DEFAULT = new Thresholds(3, 3, 4, 40, 10);
        public Thresholds {
            if (minGridLines < 2 || minGridLines > 50 || minColumns < 2 || minColumns > 20 || minRows < 2 || minRows > 100
                    || minImagePercent < 0 || minImagePercent > 100 || maxPages < 1 || maxPages > 100)
                throw new IllegalArgumentException("쪽 판단 기준값 범위 오류");
        }
    }
    /** 쪽 전체를 덮는 이미지(스캔 배경·검색 가능 PDF)는 그림으로 보지 않는다. */
    static final int BACKGROUND_IMAGE_PERCENT = 90;
    private final Thresholds thresholds;
    private final boolean always;
    private final TreeMap<Integer, String> suspects = new TreeMap<>();
    private int inspected;

    public LayoutCheck(Thresholds thresholds, boolean always) {
        this.thresholds = Objects.requireNonNull(thresholds); this.always = always;
    }
    public Thresholds thresholds() { return thresholds; }
    /** 소스별 "항상 Docling". 추출기는 신호를 재지 않고 {@link #always(int)}만 부른다. */
    public boolean always() { return always; }
    /** always 모드의 쪽. */
    public void always(int page) { inspected++; suspects.putIfAbsent(page, "always"); }
    /** 글자 층 PDF 쪽의 신호. */
    public void textPage(int page, PageSignals signals) {
        inspected++;
        if (always) { suspects.putIfAbsent(page, "always"); return; }
        String reason = decide(signals, thresholds);
        if (reason != null) suspects.putIfAbsent(page, reason);
    }
    /** 스캔 쪽(OCR 대상) 래스터의 가로·세로 직선 수. 단어 위치가 없으므로 직선 격자만으로 판단한다. */
    public void scannedPage(int page, int horizontal, int vertical) {
        inspected++;
        if (always) { suspects.putIfAbsent(page, "always"); return; }
        if (horizontal >= thresholds.minGridLines() && vertical >= thresholds.minGridLines()) suspects.putIfAbsent(page, "raster h" + horizontal + " v" + vertical);
    }
    /** 격리 자식 프로세스가 같은 기준값으로 판단한 쪽을 부모 쪽 객체에 반영한다(ML 문서 처리). */
    public void suspect(int page, String reason) {
        if (page < 1 || reason == null || reason.isEmpty()) throw new IllegalArgumentException("쪽 판단 결과 오류");
        inspected++; suspects.putIfAbsent(page, reason);
    }
    /** 의심 쪽 번호(1부터)와 짧은 근거. */
    public SortedMap<Integer, String> suspects() { return Collections.unmodifiableSortedMap(suspects); }
    /** 판단한 쪽 수(시험·상태용). */
    public int inspected() { return inspected; }

    /** 글자 층 쪽 판단. 의심이면 짧은 근거, 아니면 null. 격자 → 열 정렬 → 큰 이미지 순으로 먼저 맞는 근거 하나만 남긴다. */
    public static String decide(PageSignals s, Thresholds t) {
        if (s.horizontalLines() >= t.minGridLines() && s.verticalLines() >= t.minGridLines()) return "grid h" + s.horizontalLines() + " v" + s.verticalLines();
        if (s.columns() >= t.minColumns()) return "columns " + s.columns() + " rows " + s.rows();
        if (t.minImagePercent() > 0 && s.hasText() && s.imagePercent() >= t.minImagePercent() && s.imagePercent() < BACKGROUND_IMAGE_PERCENT)
            return "image " + s.imagePercent() + "%";
        return null;
    }
}
