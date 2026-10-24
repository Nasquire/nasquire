package com.bluesounds.nasquire.core;

import java.io.IOException;
import java.nio.file.Path;

public interface TextExtractor {
    boolean supports(Path path);
    void extract(Path path, PageConsumer consumer, int maxCharacters) throws IOException;

    @FunctionalInterface
    interface PageConsumer { void accept(int page, String text) throws IOException; }
}
