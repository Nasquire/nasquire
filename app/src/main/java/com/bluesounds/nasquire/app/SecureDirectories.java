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
        static final MethodHandle STAT = LINKER.downcallHandle(SYMBOLS.find("fstat").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        static final MethodHandle READ = LINKER.downcallHandle(SYMBOLS.find("read").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
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
    /** Linux 64비트 ABI의 fstat(st_mode)을 사용하며 알 수 없는 아키텍처는 open에서 거부한다. */
    static java.nio.channels.ReadableByteChannel readRegular(Path path) throws IOException {
        int parent = -1, file = -1;
        try (Arena arena = Arena.ofConfined()) {
            parent = open(path.getParent(), arena);
            int flags = System.getProperty("os.arch").equals("aarch64") ? (1 << 15) : (1 << 17);
            // O_NOFOLLOW는 arm64/amd64 ABI에 맞추며 O_NONBLOCK=04000이다.
            file = (int) Native.OPEN.invokeExact(parent, arena.allocateFrom(path.getFileName().toString()), flags | 2048 | (1 << 19));
            if (file < 0) throw new IOException("파일 열기 실패");
            var stat = arena.allocate(256, 8);
            int result = (int) Native.STAT.invokeExact(file, stat);
            int mode = stat.get(ValueLayout.JAVA_INT, System.getProperty("os.arch").equals("aarch64") ? 16 : 24);
            if (result != 0 || (mode & 0170000) != 0100000) throw new IOException("일반 파일만 다운로드 가능");
            final int descriptor = file; file = -1;
            return new java.nio.channels.ReadableByteChannel() {
                private boolean opened = true;
                public synchronized boolean isOpen() { return opened; }
                public synchronized void close() { if (opened) { opened = false; SecureDirectories.close(descriptor); } }
                public synchronized int read(java.nio.ByteBuffer target) throws IOException {
                    if (!opened) throw new java.nio.channels.ClosedChannelException();
                    if (!target.hasRemaining()) return 0;
                    try (Arena buffer = Arena.ofConfined()) {
                        int size = Math.min(65536, target.remaining());
                        var bytes = buffer.allocate(size);
                        long count = (long) Native.READ.invokeExact(descriptor, bytes, (long) size);
                        if (count < 0) throw new IOException("파일 읽기 실패");
                        if (count == 0) return -1;
                        target.put(bytes.asByteBuffer().limit((int)count)); return (int)count;
                    } catch (IOException e) { throw e;
                    } catch (Throwable e) { throw new IOException("파일 읽기 실패", e); }
                }
            };
        } catch (IOException e) { throw e;
        } catch (Throwable e) { throw new IOException("안전한 파일 열기 실패", e);
        } finally { close(parent); close(file); }
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
