package com.bluesounds.nasquire.core;

import java.io.IOException;
import java.nio.file.Path;

public interface TextExtractor {
    boolean supports(Path path);
    void extract(Path path, PageConsumer consumer, int maxCharacters) throws IOException;

    default void extractLocated(Path path, LocatedConsumer consumer, int maxCharacters) throws IOException {
        extract(path, (page, text) -> consumer.accept(page, Location.page(page), text), maxCharacters);
    }

    /**
     * 형식은 원래 이름({@code name})으로 정하고 내용은 {@code content}에서 읽는다. 색인기는 경로 교체 경쟁을 막으려고 열린 fd에 고정한
     * {@code /proc/self/fd/N}을 content로 넘긴다. 확장자로 형식을 고르는 추출기는 이 메서드를 재정의한다.
     */
    default void extractLocated(Path name, Path content, LocatedConsumer consumer, int maxCharacters) throws IOException {
        extractLocated(content, consumer, maxCharacters);
    }

    /**
     * 표·그림 의심 쪽 판단을 함께 하는 추출. 색인기는 Docling이 연결됐을 때만 check를 넘기고 그 밖에는 null이다(판단 코드를 돌리지 않는다).
     * 쪽이 있는 형식(PDF·스캔 이미지)의 추출기만 재정의하며 기본은 판단 없이 추출한다(HWP/HWPX·Office는 원본 표 구조를 쓰므로 대상이 아니다).
     */
    default void extractLocated(Path name, Path content, LocatedConsumer consumer, int maxCharacters,
            com.bluesounds.nasquire.core.layout.LayoutCheck check) throws IOException {
        extractLocated(name, content, consumer, maxCharacters);
    }

    @FunctionalInterface
    interface LocatedConsumer { void accept(int order, Location location, String text) throws IOException; }

    @FunctionalInterface
    interface PageConsumer { void accept(int page, String text) throws IOException; }
}
