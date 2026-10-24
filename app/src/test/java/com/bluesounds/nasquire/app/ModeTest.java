package com.bluesounds.nasquire.app;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ModeTest {
    @TempDir Path temp;
    @Test void unimplementedMlModeCannotStartAnIndexingServer() {
        Path data = temp.resolve("data");
        assertThrows(IllegalArgumentException.class, () -> new AppConfiguration().dataFolder(data.toString(), "ml"));
        assertFalse(Files.exists(data));
    }
}
