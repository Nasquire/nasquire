package com.bluesounds.nasquire.core.layout;

import java.awt.geom.*;
import java.io.IOException;
import java.util.*;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

/**
 * 글자 층 PDF 쪽의 표·그림 신호를 PDFBox로 잰다. 그리기 명령만 따라가며(렌더링 없음) 칠하거나 그은 선분·사각형 변 중 가로·세로인 것을 위치별로 모으고,
 * 그려진 이미지의 쪽 면적 비율을 잰다. 글자 열 정렬은 텍스트 추출 때 모은 글자 위치({@link Glyphs})로 계산한다.
 * 쪽 하나에서 모으는 선분·글자 수에는 상한이 있다(넘으면 그 뒤는 세지 않는다).
 */
public final class PdfLayout {
    private PdfLayout() { }
    /** 선 하나로 볼 위치의 최소 누적 길이(pt, 0.5인치)와 같은 위치로 묶는 허용 오차(pt). 얇은 사각형은 선으로 본다. */
    static final float MIN_LINE = 36, SAME = 2, THIN = 3, AXIS = 1;
    static final int MAX_SEGMENTS = 20_000, MAX_GLYPHS = 20_000;

    /** 쪽 하나의 글자 위치(왼쪽 x, 기준선 y, 너비, 글자 크기). 텍스트 추출기가 채운다. */
    public static final class Glyphs {
        private final float[] values = new float[MAX_GLYPHS * 4];
        private int size;
        public void add(float x, float y, float width, float fontSize) {
            if (size >= MAX_GLYPHS || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width) || !Float.isFinite(fontSize)) return;
            int i = size++ * 4; values[i] = x; values[i + 1] = y; values[i + 2] = Math.max(0, width); values[i + 3] = Math.max(1, fontSize);
        }
        public void clear() { size = 0; }
        int size() { return size; }
    }

    /** 쪽 하나의 신호를 잰다. glyphs는 그 쪽 글자 위치, hasText는 추출 글자 유무다. */
    public static PageSignals inspect(PDPage page, Glyphs glyphs, boolean hasText, int minRows) throws IOException {
        var engine = new Engine(page);
        engine.processPage(page);
        int[] columns = columns(glyphs, minRows);
        return new PageSignals(positions(engine.horizontal), positions(engine.vertical), columns[0], columns[1], engine.imagePercent(), hasText);
    }

    /**
     * 글자 열 정렬 {열 수, 여러 조각으로 나뉜 줄 수}. 같은 기준선(2pt 안)의 글자를 한 줄로 묶고 글자 크기의 1.5배를 넘는 간격에서 조각을 나눈다.
     * 둘 이상의 조각으로 나뉜 줄에서 조각 시작 x를 3pt 단위로 모아, minRows 줄 이상이 같은 x에서 시작하면 열 하나로 센다.
     * 일반 문단(줄마다 조각 하나)과 2단 편집(열 2개)은 기본 기준값(열 3개)에 걸리지 않는다.
     */
    static int[] columns(Glyphs glyphs, int minRows) {
        int n = glyphs.size;
        if (n == 0) return new int[] {0, 0};
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        float[] v = glyphs.values;
        Arrays.sort(order, Comparator.<Integer>comparingDouble(i -> v[i * 4 + 1]).thenComparingDouble(i -> v[i * 4]));
        var starts = new HashMap<Integer, Integer>();
        int split = 0, i = 0;
        while (i < n) {
            int j = i;
            float y = v[order[i] * 4 + 1];
            while (j < n && Math.abs(v[order[j] * 4 + 1] - y) <= SAME) j++;
            // 한 줄 [i, j): x 순으로 다시 정렬해 큰 간격에서 나눈다.
            Integer[] line = Arrays.copyOfRange(order, i, j);
            Arrays.sort(line, Comparator.comparingDouble(k -> v[k * 4]));
            var segments = new ArrayList<Float>();
            segments.add(v[line[0] * 4]);
            float end = v[line[0] * 4] + v[line[0] * 4 + 2];
            for (int k = 1; k < line.length; k++) {
                int g = line[k] * 4;
                if (v[g] - end > 1.5f * v[line[k - 1] * 4 + 3]) segments.add(v[g]);
                end = Math.max(end, v[g] + v[g + 2]);
            }
            if (segments.size() > 1) {
                split++;
                var seen = new HashSet<Integer>();
                for (float x : segments) { int bucket = Math.round(x / 3); if (seen.add(bucket)) starts.merge(bucket, 1, Integer::sum); }
            }
            i = j;
        }
        // 이웃한 칸(반올림 경계)을 합쳐 열마다 한 번만 센다.
        var buckets = new TreeMap<>(starts);
        int count = 0; Integer last = null; int run = 0;
        for (var e : buckets.entrySet()) {
            if (last != null && e.getKey() - last <= 1) run += e.getValue();
            else { if (run >= minRows) count++; run = e.getValue(); }
            last = e.getKey();
        }
        if (run >= minRows) count++;
        return new int[] {count, split};
    }

    /** 위치별(2pt 안) 누적 길이가 기준 이상인 선 위치 수. 값은 {위치, 시작, 끝}. */
    static int positions(List<float[]> segments) {
        if (segments.isEmpty()) return 0;
        var sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingDouble(s -> s[0]));
        int count = 0; int i = 0;
        while (i < sorted.size()) {
            float at = sorted.get(i)[0];
            var ranges = new ArrayList<float[]>();
            int j = i;
            while (j < sorted.size() && sorted.get(j)[0] - at <= SAME) ranges.add(sorted.get(j++));
            if (covered(ranges) >= MIN_LINE) count++;
            i = j;
        }
        return count;
    }
    /** 겹치는 구간을 합친 길이. */
    private static float covered(List<float[]> ranges) {
        ranges.sort(Comparator.comparingDouble(r -> r[1]));
        float total = 0, start = ranges.getFirst()[1], end = ranges.getFirst()[2];
        for (var r : ranges.subList(1, ranges.size())) {
            if (r[1] > end) { total += end - start; start = r[1]; end = r[2]; }
            else end = Math.max(end, r[2]);
        }
        return total + end - start;
    }

    /** 경로를 모아 그을 때(stroke)는 모든 선분, 칠할 때(fill)는 얇은 사각형만 선으로 센다. 큰 칠(배경·칸 음영)은 세지 않는다. */
    private static final class Engine extends PDFGraphicsStreamEngine {
        final List<float[]> horizontal = new ArrayList<>(), vertical = new ArrayList<>();
        private final List<float[]> path = new ArrayList<>();
        private final List<Rectangle2D> rectangles = new ArrayList<>();
        private final Rectangle2D area;
        private float x, y, startX, startY;
        private double largestImage;
        Engine(PDPage page) {
            super(page);
            var box = page.getCropBox();
            area = new Rectangle2D.Float(box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight());
        }
        int imagePercent() {
            double total = area.getWidth() * area.getHeight();
            return total <= 0 ? 0 : (int)Math.min(100, Math.round(largestImage * 100 / total));
        }
        private void segment(float x1, float y1, float x2, float y2) {
            if (path.size() < MAX_SEGMENTS) path.add(new float[] {x1, y1, x2, y2});
        }
        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            var r = new Path2D.Double(); r.moveTo(p0.getX(), p0.getY()); r.lineTo(p1.getX(), p1.getY()); r.lineTo(p2.getX(), p2.getY()); r.lineTo(p3.getX(), p3.getY());
            if (rectangles.size() < MAX_SEGMENTS) rectangles.add(r.getBounds2D());
            segment((float)p0.getX(), (float)p0.getY(), (float)p1.getX(), (float)p1.getY());
            segment((float)p1.getX(), (float)p1.getY(), (float)p2.getX(), (float)p2.getY());
            segment((float)p2.getX(), (float)p2.getY(), (float)p3.getX(), (float)p3.getY());
            segment((float)p3.getX(), (float)p3.getY(), (float)p0.getX(), (float)p0.getY());
            x = startX = (float)p0.getX(); y = startY = (float)p0.getY();
        }
        @Override public void drawImage(PDImage image) {
            var m = getGraphicsState().getCurrentTransformationMatrix().createAffineTransform();
            var bounds = m.createTransformedShape(new Rectangle2D.Double(0, 0, 1, 1)).getBounds2D().createIntersection(area);
            if (bounds.getWidth() > 0 && bounds.getHeight() > 0) largestImage = Math.max(largestImage, bounds.getWidth() * bounds.getHeight());
        }
        @Override public void clip(int windingRule) { }
        @Override public void moveTo(float x, float y) { this.x = startX = x; this.y = startY = y; }
        @Override public void lineTo(float x, float y) { segment(this.x, this.y, x, y); this.x = x; this.y = y; }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { x = x3; y = y3; }
        @Override public Point2D getCurrentPoint() { return new Point2D.Float(x, y); }
        @Override public void closePath() { segment(x, y, startX, startY); x = startX; y = startY; }
        @Override public void endPath() { clear(); }
        @Override public void strokePath() { commit(true); }
        @Override public void fillPath(int windingRule) { commit(false); }
        @Override public void fillAndStrokePath(int windingRule) { commit(true); }
        @Override public void shadingFill(COSName shadingName) { }
        private void commit(boolean stroked) {
            if (stroked) for (var s : path) line(s[0], s[1], s[2], s[3]);
            else for (var r : rectangles) {
                // 칠한 얇은 사각형(표 선을 사각형으로 그리는 문서)만 선으로 본다.
                if (r.getHeight() <= THIN && r.getWidth() > THIN) line((float)r.getMinX(), (float)r.getCenterY(), (float)r.getMaxX(), (float)r.getCenterY());
                else if (r.getWidth() <= THIN && r.getHeight() > THIN) line((float)r.getCenterX(), (float)r.getMinY(), (float)r.getCenterX(), (float)r.getMaxY());
            }
            clear();
        }
        private void line(float x1, float y1, float x2, float y2) {
            if (horizontal.size() + vertical.size() >= MAX_SEGMENTS) return;
            if (Math.abs(y1 - y2) <= AXIS && Math.abs(x1 - x2) > AXIS) horizontal.add(new float[] {(y1 + y2) / 2, Math.min(x1, x2), Math.max(x1, x2)});
            else if (Math.abs(x1 - x2) <= AXIS && Math.abs(y1 - y2) > AXIS) vertical.add(new float[] {(x1 + x2) / 2, Math.min(y1, y2), Math.max(y1, y2)});
        }
        private void clear() { path.clear(); rectangles.clear(); }
    }
}
