package com.bluesounds.nasquire.app;

import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ModeTest {
    @TempDir Path temp;
    @Test void searchContextRejectsOtherModes() {
        Path data = temp.resolve("data");
        assertThrows(IllegalArgumentException.class, () -> new AppConfiguration().dataFolder(data.toString(), "ml"));
        assertFalse(Files.exists(data));
    }
    @Test void mlModeIsRoutedBeforeSpringAndRefusesWithoutFullOrToken() {
        assertEquals("ml", NasquireApplication.mode(new String[] {"--nasquire.mode=ml"}, Map.of()));
        assertEquals("ml", NasquireApplication.mode(new String[0], Map.of("NASQUIRE_MODE", "ml")));
        assertEquals("search", NasquireApplication.mode(new String[0], Map.of()));
        Path data = temp.resolve("ml-data");
        // lite는 full 미포함, full은 토큰 미설정으로 시작을 거부한다. 어느 쪽도 검색 서버를 띄우지 않는다.
        assertThrows(RuntimeException.class, () -> NasquireApplication.main(new String[] {"--nasquire.mode=ml", "--nasquire.data=" + data, "--server.port=0", "--nasquire.ml.embedding=builtin"}));
        assertFalse(Files.exists(data));
    }
}
