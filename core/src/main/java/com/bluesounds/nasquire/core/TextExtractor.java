package com.bluesounds.nasquire.core;

import java.io.IOException;
import java.nio.file.Path;

public interface TextExtractor {
    boolean supports(Path path);
    void extract(Path path, PageConsumer consumer, int maxCharacters) throws IOException;

    default void extractLocated(Path path, LocatedConsumer consumer, int maxCharacters) throws IOException {
        extract(path, (page, text) -> consumer.accept(page, Location.page(page), text), maxCharacters);
    }

    @FunctionalInterface
    interface LocatedConsumer { void accept(int order, Location location, String text) throws IOException; }

    @FunctionalInterface
    interface PageConsumer { void accept(int page, String text) throws IOException; }
}
