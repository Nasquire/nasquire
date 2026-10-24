package com.bluesounds.nasquire.app;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/** Linux mkdirat: Java SecureDirectoryStream에 없는 폴더 생성도 NOFOLLOW로 고정한 부모에서 수행한다. */
final class SecureDirectories {
    // Linux UAPI의 amd64/arm64 상수 차이를 구분한다. O_RDONLY=0.
    private static final int FLAGS = System.getProperty("os.arch").equals("aarch64")
            ? (1 << 14) | (1 << 15) | (1 << 19) : (1 << 16) | (1 << 17) | (1 << 19);
    private static final class Native {
        static final Linker LINKER = Linker.nativeLinker();
        static final SymbolLookup SYMBOLS = LINKER.defaultLookup();
        static final MethodHandle OPEN = LINKER.downcallHandle(SYMBOLS.find("openat").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        static final MethodHandle MKDIR = LINKER.downcallHandle(SYMBOLS.find("mkdirat").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        static final MethodHandle MOVE = LINKER.downcallHandle(SYMBOLS.find("renameat2").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        static final MethodHandle CLOSE = LINKER.downcallHandle(SYMBOLS.find("close").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }
    private static int open(Path path, Arena arena) throws Throwable {
        if (!System.getProperty("os.name").equals("Linux") || !java.util.Set.of("amd64", "x86_64", "aarch64").contains(System.getProperty("os.arch")) || !path.isAbsolute()) throw new IOException("안전한 파일 작업 미지원");
        int fd = (int) Native.OPEN.invokeExact(-100, arena.allocateFrom("/"), FLAGS);
        if (fd < 0) throw new IOException("루트 열기 실패");
        try {
            for (Path component : path) {
                int next = (int) Native.OPEN.invokeExact(fd, arena.allocateFrom(component.toString()), FLAGS);
                if (next < 0) throw new IOException("부모 디렉터리 열기 실패");
                close(fd); fd = next;
            }
            return fd;
        } catch (Throwable e) { close(fd); throw e; }
    }
    private static void close(int fd) {
        if (fd >= 0) try { int ignored = (int) Native.CLOSE.invokeExact(fd); } catch (Throwable ignored) { }
    }
    /** RENAME_NOREPLACE로 검사 뒤 대상이 생겨도 명시 없는 덮어쓰기를 방지한다. */
    static void moveNew(Path from, Path to) throws IOException {
        int origin = -1, target = -1;
        try (Arena arena = Arena.ofConfined()) {
            origin = open(from.getParent(), arena); target = open(to.getParent(), arena);
            int result = (int) Native.MOVE.invokeExact(origin, arena.allocateFrom(from.getFileName().toString()),
                    target, arena.allocateFrom(to.getFileName().toString()), 1);
            if (result != 0) throw new IOException("원자적 이동 실패 또는 대상 존재");
        } catch (IOException e) { throw e;
        } catch (Throwable e) { throw new IOException("안전한 이동 실패", e);
        } finally { close(origin); close(target); }
    }
    static void create(Path absolute) throws IOException {
        if (!System.getProperty("os.name").equals("Linux") || !absolute.isAbsolute()) throw new IOException("안전한 폴더 생성 미지원");
        int fd = -1;
        try (Arena arena = Arena.ofConfined()) {
            fd = open(absolute.getParent(), arena);
            int result = (int) Native.MKDIR.invokeExact(fd, arena.allocateFrom(absolute.getFileName().toString()), 0700);
            if (result != 0) throw new IOException("폴더 생성 실패 또는 대상 존재");
        } catch (IOException e) { throw e;
        } catch (Throwable e) { throw new IOException("안전한 폴더 생성 실패", e);
        } finally {
            close(fd);
        }
    }
}
