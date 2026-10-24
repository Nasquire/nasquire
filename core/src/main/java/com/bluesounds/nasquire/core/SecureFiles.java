package com.bluesounds.nasquire.core;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.Set;

/**
 * Linux amd64/arm64 FFM openat·mkdirat·renameat2·fstat로 경로 교체 경쟁 없이 파일을 다룬다. 절대 경로의 모든 요소를 "/"부터
 * O_NOFOLLOW·O_DIRECTORY로 하나씩 열어 고정하므로 검사 뒤 중간 디렉터리를 링크로 바꿔도 소스 밖으로 나가지 않는다. 마지막 파일은
 * O_NOFOLLOW|O_NONBLOCK으로 열어 FIFO로 바뀌어도 멈추지 않고, 열린 fd의 fstat으로 일반 파일만 허용한다.
 * 미지원 환경은 {@link IOException}으로 실패한다. {@code --enable-native-access=ALL-UNNAMED}가 필요하다.
 */
public final class SecureFiles {
    private SecureFiles() { }
    private static final boolean ARM = "aarch64".equals(System.getProperty("os.arch"));
    // Linux UAPI의 amd64/arm64 상수 차이를 구분한다. O_RDONLY=0, O_NONBLOCK=04000, O_CLOEXEC=02000000.
    private static final int O_DIRECTORY = ARM ? 1 << 14 : 1 << 16, O_NOFOLLOW = ARM ? 1 << 15 : 1 << 17,
            O_NONBLOCK = 2048, O_CLOEXEC = 1 << 19, AT_FDCWD = -100, RENAME_NOREPLACE = 1;
    private static final int DIRECTORY_FLAGS = O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC, FILE_FLAGS = O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC;
    /** Linux 64비트 ABI struct stat: st_dev 0, st_ino 8, st_mode amd64 24·arm64 16, st_size 48. */
    private static final int MODE_OFFSET = ARM ? 16 : 24;

    private static final class Native {
        static final Linker LINKER = Linker.nativeLinker();
        static final SymbolLookup SYMBOLS = LINKER.defaultLookup();
        static MethodHandle handle(String name, FunctionDescriptor descriptor) { return LINKER.downcallHandle(SYMBOLS.find(name).orElseThrow(), descriptor); }
        static final MethodHandle OPEN = handle("openat", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        static final MethodHandle MKDIR = handle("mkdirat", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        static final MethodHandle MOVE = handle("renameat2", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        static final MethodHandle STAT = handle("fstat", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        static final MethodHandle READ = handle("read", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        static final MethodHandle CLOSE = handle("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }

    public static boolean supported() {
        return "Linux".equals(System.getProperty("os.name")) && Set.of("amd64", "x86_64", "aarch64").contains(System.getProperty("os.arch"));
    }
    private static void requireSupported(Path path) throws IOException {
        if (!supported() || !path.isAbsolute()) throw new IOException("안전한 파일 작업 미지원");
    }

    /** "/"부터 각 요소를 NOFOLLOW 디렉터리로 열어 마지막 디렉터리 fd를 돌려준다. */
    private static int directory(Path absolute, Arena arena) throws Throwable {
        requireSupported(absolute);
        int fd = (int) Native.OPEN.invokeExact(AT_FDCWD, arena.allocateFrom("/"), DIRECTORY_FLAGS);
        if (fd < 0) throw new IOException("루트 열기 실패");
        try {
            for (Path component : absolute) {
                int next = (int) Native.OPEN.invokeExact(fd, arena.allocateFrom(component.toString()), DIRECTORY_FLAGS);
                if (next < 0) throw new IOException("부모 디렉터리 열기 실패");
                close(fd); fd = next;
            }
            return fd;
        } catch (Throwable e) { close(fd); throw e; }
    }
    private static void close(int fd) {
        if (fd >= 0) try { int ignored = (int) Native.CLOSE.invokeExact(fd); } catch (Throwable ignored) { }
    }

    /** 경로 대신 열린 fd에 고정된 일반 파일. {@link #path()}는 같은 inode를 다시 여는 /proc/self/fd 경로다. */
    public static final class Pinned implements AutoCloseable {
        private int fd;
        private final long device, inode, size;
        private Pinned(int fd, long device, long inode, long size) { this.fd = fd; this.device = device; this.inode = inode; this.size = size; }
        /** 이 경로를 여는 것은 고정된 inode를 여는 것이다(경로 교체와 무관). 닫은 뒤에는 쓰지 않는다. */
        public synchronized Path path() {
            if (fd < 0) throw new IllegalStateException("닫힌 파일");
            return Path.of("/proc/self/fd/" + fd);
        }
        public String fileKey() { return key(device, inode); }
        public long device() { return device; }
        public long inode() { return inode; }
        public long size() { return size; }
        /** fd를 넘겨받는 읽기 채널. 채널을 닫으면 fd가 닫힌다. */
        public synchronized ReadableByteChannel channel() {
            if (fd < 0) throw new IllegalStateException("닫힌 파일");
            int owned = fd; fd = -1;
            return new FdChannel(owned);
        }
        public synchronized void close() { SecureFiles.close(fd); fd = -1; }
    }

    private static String key(long device, long inode) {
        return "(dev=" + Long.toHexString(device) + ",ino=" + inode + ")";
    }

    /** 확인한 공유 루트 fd를 유지하며 하위 경로를 openat·NOFOLLOW로만 연다. */
    public static final class Directory implements AutoCloseable {
        private int fd;
        private final String fileKey;
        private Directory(int fd, String fileKey) { this.fd = fd; this.fileKey = fileKey; }
        public String fileKey() { return fileKey; }
        public synchronized Path path() throws IOException {
            if (fd < 0) throw new IOException("닫힌 디렉터리");
            return Path.of("/proc/self/fd/" + fd);
        }
        public synchronized Pinned open(Path relative) throws IOException {
            if (fd < 0 || relative.isAbsolute() || relative.toString().isEmpty()) throw new IOException("상대 파일 경로 필요");
            int parent = -1, file = -1;
            try (Arena arena = Arena.ofConfined()) {
                parent = (int) Native.OPEN.invokeExact(fd, arena.allocateFrom("."), DIRECTORY_FLAGS);
                if (parent < 0) throw new IOException("공유 루트 열기 실패");
                for (int i = 0; i < relative.getNameCount(); i++) {
                    String part = relative.getName(i).toString();
                    if (part.equals(".") || part.equals("..") || part.isEmpty()) throw new IOException("상대 파일 경로 오류");
                    if (i == relative.getNameCount() - 1) {
                        file = (int) Native.OPEN.invokeExact(parent, arena.allocateFrom(part), FILE_FLAGS);
                    } else {
                        int next = (int) Native.OPEN.invokeExact(parent, arena.allocateFrom(part), DIRECTORY_FLAGS);
                        if (next < 0) throw new IOException("하위 디렉터리 열기 실패");
                        SecureFiles.close(parent); parent = next;
                    }
                }
                Pinned result = regular(file, arena); file = -1; return result;
            } catch (IOException e) { throw e;
            } catch (Throwable e) { throw new IOException("공유 파일 열기 실패", e);
            } finally { SecureFiles.close(parent); SecureFiles.close(file); }
        }
        public synchronized void close() { SecureFiles.close(fd); fd = -1; }
    }

    public static Directory openDirectory(Path absolute) throws IOException {
        int fd = -1;
        try (Arena arena = Arena.ofConfined()) {
            fd = directory(absolute, arena);
            var stat = arena.allocate(256, 8);
            if ((int) Native.STAT.invokeExact(fd, stat) != 0) throw new IOException("디렉터리 식별자 확인 실패");
            var result = new Directory(fd, key(stat.get(ValueLayout.JAVA_LONG, 0), stat.get(ValueLayout.JAVA_LONG, 8)));
            fd = -1; return result;
        } catch (IOException e) { throw e;
        } catch (Throwable e) { throw new IOException("공유 루트 열기 실패", e);
        } finally { close(fd); }
    }

    private static Pinned regular(int fd, Arena arena) throws Throwable {
        if (fd < 0) throw new IOException("파일 열기 실패");
        var stat = arena.allocate(256, 8);
        int result = (int) Native.STAT.invokeExact(fd, stat);
        if (result != 0 || (stat.get(ValueLayout.JAVA_INT, MODE_OFFSET) & 0170000) != 0100000) throw new IOException("일반 파일이 아닙니다");
        return new Pinned(fd, stat.get(ValueLayout.JAVA_LONG, 0), stat.get(ValueLayout.JAVA_LONG, 8), stat.get(ValueLayout.JAVA_LONG, 48));
    }

    /**
     * 절대 경로의 일반 파일을 고정해 연다. 모든 조상은 NOFOLLOW로 "/"부터 따라가므로 경로에 링크가 있으면(설정한 루트 포함) 실패한다.
     * 이미 고정된 {@code /proc/self/fd/N}은 그 fd가 가리키는 inode만 다시 연다.
     */
    public static Pinned open(Path absolute) throws IOException {
        requireSupported(absolute);
        int parent = -1, file = -1;
        try (Arena arena = Arena.ofConfined()) {
            if (pinnedPath(absolute)) file = (int) Native.OPEN.invokeExact(AT_FDCWD, arena.allocateFrom(absolute.toString()), O_NONBLOCK | O_CLOEXEC);
            else {
                if (absolute.getParent() == null || absolute.getFileName() == null) throw new IOException("파일 경로 오류");
                parent = directory(absolute.getParent(), arena);
                file = (int) Native.OPEN.invokeExact(parent, arena.allocateFrom(absolute.getFileName().toString()), FILE_FLAGS);
            }
            var pinned = regular(file, arena);
            file = -1;
            return pinned;
        } catch (IOException e) { throw e;
        } catch (Throwable e) { throw new IOException("안전한 파일 열기 실패", e);
        } finally { close(parent); close(file); }
    }
    static boolean pinnedPath(Path path) { return path.toString().matches("/proc/self/fd/[0-9]{1,9}"); }

    /** {@link #open}으로 연 일반 파일의 읽기 채널. */
    public static ReadableByteChannel readRegular(Path absolute) throws IOException { return open(absolute).channel(); }

    /** RENAME_NOREPLACE로 검사 뒤 대상이 생겨도 명시 없는 덮어쓰기를 방지한다. */
    public static void moveNew(Path from, Path to) throws IOException {
        int origin = -1, target = -1;
        try (Arena arena = Arena.ofConfined()) {
            origin = directory(from.getParent(), arena); target = directory(to.getParent(), arena);
            int result = (int) Native.MOVE.invokeExact(origin, arena.allocateFrom(from.getFileName().toString()),
                    target, arena.allocateFrom(to.getFileName().toString()), RENAME_NOREPLACE);
            if (result != 0) throw new IOException("원자적 이동 실패 또는 대상 존재");
        } catch (IOException e) { throw e;
        } catch (Throwable e) { throw new IOException("안전한 이동 실패", e);
        } finally { close(origin); close(target); }
    }

    /** 고정한 부모에서 mkdirat(0700). Java SecureDirectoryStream에 없는 폴더 생성을 NOFOLLOW로 수행한다. */
    public static void createDirectory(Path absolute) throws IOException {
        int fd = -1;
        try (Arena arena = Arena.ofConfined()) {
            fd = directory(absolute.getParent(), arena);
            int result = (int) Native.MKDIR.invokeExact(fd, arena.allocateFrom(absolute.getFileName().toString()), 0700);
            if (result != 0) throw new IOException("폴더 생성 실패 또는 대상 존재");
        } catch (IOException e) { throw e;
        } catch (Throwable e) { throw new IOException("안전한 폴더 생성 실패", e);
        } finally { close(fd); }
    }

    private static final class FdChannel implements ReadableByteChannel {
        private final int descriptor;
        private boolean opened = true;
        FdChannel(int descriptor) { this.descriptor = descriptor; }
        public synchronized boolean isOpen() { return opened; }
        public synchronized void close() { if (opened) { opened = false; SecureFiles.close(descriptor); } }
        public synchronized int read(ByteBuffer target) throws IOException {
            if (!opened) throw new ClosedChannelException();
            if (!target.hasRemaining()) return 0;
            try (Arena buffer = Arena.ofConfined()) {
                int size = Math.min(65536, target.remaining());
                var bytes = buffer.allocate(size);
                long count = (long) Native.READ.invokeExact(descriptor, bytes, (long) size);
                if (count < 0) throw new IOException("파일 읽기 실패");
                if (count == 0) return -1;
                target.put(bytes.asByteBuffer().limit((int) count)); return (int) count;
            } catch (IOException e) { throw e;
            } catch (Throwable e) { throw new IOException("파일 읽기 실패", e); }
        }
    }
}
