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

    @FunctionalInterface
    interface LocatedConsumer { void accept(int order, Location location, String text) throws IOException; }

    @FunctionalInterface
    interface PageConsumer { void accept(int page, String text) throws IOException; }
}
