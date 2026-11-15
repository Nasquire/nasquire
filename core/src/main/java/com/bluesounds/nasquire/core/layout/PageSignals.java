package com.bluesounds.nasquire.core.layout;

/**
 * 글자 층 PDF 쪽 하나의 신호. horizontalLines·verticalLines는 길이 기준을 넘는 서로 다른 위치의 가로·세로 벡터 선(선분·사각형 변) 수,
 * columns는 기준 줄 수 이상 같은 x에서 시작하는 글자 열 수, rows는 큰 간격으로 둘 이상 나뉜 줄 수, imagePercent는 가장 큰 이미지가 쪽에서
 * 차지하는 비율(0~100), hasText는 쪽에 추출 글자가 있는지다.
 */
public record PageSignals(int horizontalLines, int verticalLines, int columns, int rows, int imagePercent, boolean hasText) { }
