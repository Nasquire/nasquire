package com.bluesounds.nasquire.core;

/** 형식별 근거. 문단 번호는 문서 순서이며 실제 인쇄 쪽을 추정하지 않는다. */
public record Location(String type, Integer page, Integer slide, String sheet, Integer rowFrom,
        Integer rowTo, Integer paragraph, String heading) {
    public static Location page(int page) { return new Location("page", page, null, null, null, null, null, null); }
    public static Location slide(int slide) { return new Location("slide", null, slide, null, null, null, null, null); }
    public static Location row(String sheet, int row) { return new Location("rows", null, null, sheet, row, row, null, null); }
    public static Location paragraph(int paragraph, String heading) { return new Location("paragraph", null, null, null, null, null, paragraph, heading); }
}
