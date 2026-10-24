package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShareRaceTest {
    @TempDir Path temp;
    Storage storage;
    DownloadService downloads;
    JdbcTemplate db;
    Source source;

    @BeforeEach void setup() throws Exception {
        storage = mock(Storage.class); downloads = mock(DownloadService.class);
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        source = new Source("source", "Source", temp, null, null);
        when(storage.source("source")).thenReturn(source);
    }
    private String token(ShareService service, String path) throws Exception {
        return service.create("source", path, null, null, null).get("url").toString().substring(3);
    }
    private void replaceFile() throws IOException {
        Files.move(temp.resolve("file.txt"), temp.resolve("saved.txt"));
        Files.writeString(temp.resolve("file.txt"), "교체된 내용");
    }
    private void replaceDirectory() throws IOException {
        Files.move(temp.resolve("folder"), temp.resolve("saved"));
        Files.createDirectory(temp.resolve("folder"));
        Files.writeString(temp.resolve("folder/file.txt"), "교체된 내용");
    }

    @Test void replacementAfterRefreshNeverPassesOpenedFileIdentity() throws Exception {
        Files.writeString(temp.resolve("file.txt"), "원본");
        var service = new ShareService(db, storage, downloads, Clock.systemUTC()) {
            @Override SecureFiles.Pinned openPinned(Target target) throws IOException {
                replaceFile(); // 경로 검사 직후, 실제 fd 열기 직전의 경쟁을 재현한다.
                return super.openPinned(target);
            }
        };
        var target = service.authorize(token(service, "file.txt"), "", null);
        assertThrows(ShareService.Missing.class, () -> service.open(target));
        assertEquals(0L, db.queryForObject("SELECT downloads FROM shares", Long.class));
    }

    @Test void replacementAfterFdCheckStillSendsOnlyOriginalInode() throws Exception {
        Files.writeString(temp.resolve("file.txt"), "원본");
        var service = new ShareService(db, storage, downloads, Clock.systemUTC()) {
            @Override SecureFiles.Pinned openPinned(Target target) throws IOException {
                var file = super.openPinned(target);
                replaceFile();
                return file;
            }
        };
        try (var channel = service.open(service.authorize(token(service, "file.txt"), "", null))) {
            assertEquals("원본", new String(Channels.newInputStream(channel).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test void checkedDirectoryHandleAnchorsDownloadsAndListingsAfterReplacement() throws Exception {
        Files.createDirectory(temp.resolve("folder"));
        Files.writeString(temp.resolve("folder/file.txt"), "원본");
        var service = new ShareService(db, storage, downloads, Clock.systemUTC()) {
            @Override SecureFiles.Directory openSharedRoot(Target target) throws IOException {
                var root = super.openSharedRoot(target);
                replaceDirectory(); // 식별자를 확인한 루트 핸들을 확보한 직후 교체한다.
                return root;
            }
        };
        String token = token(service, "folder");
        try (var channel = service.open(service.authorize(token, "file.txt", null))) {
            assertEquals("원본", new String(Channels.newInputStream(channel).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        Files.move(temp.resolve("folder"), temp.resolve("replacement"));
        Files.move(temp.resolve("saved"), temp.resolve("folder"));
        Files.writeString(temp.resolve("folder/original.txt"), "원본 목록");
        var names = service.entries(service.authorize(token, "", null)).stream().map(row -> row.get("name")).toList();
        assertTrue(names.contains("original.txt"));
    }

    @Test void pinnedDirectoryNeverFollowsChildLinksOrOpensFifo() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "범위 밖");
        Path folder = Files.createDirectory(temp.resolve("folder"));
        Files.createSymbolicLink(folder.resolve("link"), outside);
        Path fifo = folder.resolve("file.txt");
        assertEquals(0, new ProcessBuilder("/usr/bin/mkfifo", fifo.toString()).start().waitFor());
        try (var root = SecureFiles.openDirectory(folder)) {
            assertThrows(IOException.class, () -> root.open(Path.of("link/secret.txt")));
            assertThrows(IOException.class, () -> root.open(Path.of("../outside/secret.txt")));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
                    () -> assertThrows(IOException.class, () -> root.open(Path.of("file.txt"))));
        }
    }

    @Test void replacedDirectoryBeforeHandleCheckIsRejected() throws Exception {
        Files.createDirectory(temp.resolve("folder"));
        Files.writeString(temp.resolve("folder/file.txt"), "원본");
        var service = new ShareService(db, storage, downloads, Clock.systemUTC()) {
            @Override SecureFiles.Directory openSharedRoot(Target target) throws IOException {
                replaceDirectory();
                return super.openSharedRoot(target);
            }
        };
        var target = service.authorize(token(service, "folder"), "file.txt", null);
        assertThrows(ShareService.Missing.class, () -> service.open(target));
    }
}
