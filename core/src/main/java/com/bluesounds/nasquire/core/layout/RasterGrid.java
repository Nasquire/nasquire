package com.bluesounds.nasquire.core.layout;

import java.awt.image.BufferedImage;

/**
 * 스캔 쪽 래스터의 가로·세로 직선 격자 검출. 어두운 화소의 긴 연속 구간(작은 끊김 허용)이 있는 행·열을 직선으로 보고, 붙어 있는 행·열(굵은 선)은
 * 하나로 합쳐 센다. 가로선은 쪽 너비의 1/5 이상, 세로선은 쪽 높이의 1/12 이상이어야 하므로 글자 획·밑줄 몇 개로는 격자가 되지 않는다.
 * OCR 자식 프로세스가 이미 렌더링·디코딩한 이미지에서 계산한다(부모 JVM은 결과 숫자만 읽는다).
 */
public final class RasterGrid {
    private RasterGrid() { }
    /** 이 밝기(0~255) 미만을 어두운 화소로 본다. */
    static final int DARK = 128;
    /** 끊김 허용 화소 수(스캔 잡음·점선 일부). */
    static final int GAP = 2;
    static final int MIN_LENGTH = 40;

    /** 가로·세로 직선 수 {horizontal, vertical}. */
    public static int[] lines(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight();
        if (width < 8 || height < 8) return new int[] {0, 0};
        byte[] gray = gray(image);
        int horizontal = count(gray, width, height, true, Math.max(MIN_LENGTH, width / 5));
        int vertical = count(gray, width, height, false, Math.max(MIN_LENGTH, height / 12));
        return new int[] {horizontal, vertical};
    }

    /** 1바이트 회색조 화소. TYPE_BYTE_GRAY는 그대로 읽고 그 밖의 형식은 행 단위로 변환한다. */
    private static byte[] gray(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight();
        byte[] out = new byte[width * height];
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            image.getRaster().getDataElements(0, 0, width, height, out);
            return out;
        }
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int rgb = row[x], alpha = rgb >>> 24;
                int luminance = alpha < 128 ? 255 : (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
                out[y * width + x] = (byte)luminance;
            }
        }
        return out;
    }

    private static int count(byte[] gray, int width, int height, boolean horizontal, int minLength) {
        int outer = horizontal ? height : width, inner = horizontal ? width : height;
        int lines = 0; boolean previous = false;
        for (int i = 0; i < outer; i++) {
            int run = 0, gap = 0; boolean line = false;
            for (int j = 0; j < inner && !line; j++) {
                int value = gray[horizontal ? i * width + j : j * width + i] & 0xFF;
                if (value < DARK) { run += gap + 1; gap = 0; if (run >= minLength) line = true; }
                else if (run > 0 && ++gap > GAP) { run = 0; gap = 0; }
            }
            if (line && !previous) lines++;
            previous = line;
        }
        return lines;
    }
}
