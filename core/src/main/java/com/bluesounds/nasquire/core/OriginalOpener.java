package com.bluesounds.nasquire.core;

import java.io.IOException;
import java.nio.file.Path;

/** 호출자는 NOFOLLOW·일반 파일·고정 핸들 및 읽기 전용 보장을 유지해야 한다. */
@FunctionalInterface
public interface OriginalOpener {
    Opened open(Path original) throws IOException;
    interface Opened extends AutoCloseable {
        Path path();
        void close() throws IOException;
    }
    static OriginalOpener secure() {
        return original -> {
            var pinned = SecureFiles.open(original);
            return new Opened() {
                public Path path() { return pinned.path(); }
                public void close() throws IOException { pinned.close(); }
            };
        };
    }
}
