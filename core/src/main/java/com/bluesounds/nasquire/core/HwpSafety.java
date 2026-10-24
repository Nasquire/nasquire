package com.bluesounds.nasquire.core;

import java.io.*;
import java.nio.*;
import java.nio.file.Path;
import java.util.zip.*;
import org.apache.poi.poifs.filesystem.*;

/** hwplib를 호출하기 전에 OLE 스트림과 HWP 압축 레코드 크기를 제한한다. */
final class HwpSafety {
    private static final int MAX = 8 * 1024 * 1024;
    static void check(Path path) throws IOException {
        try (var fs = new POIFSFileSystem(path.toFile(), true)) {
            byte[] header;
            try (var in = fs.createDocumentInputStream("FileHeader")) { header = in.readNBytes(256); }
            if (header.length < 40) throw new IOException("HWP 헤더 손상");
            int flags = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(36);
            if ((flags & (2 | 4 | 16 | 256 | 1024)) != 0) throw new IOException("암호·배포·DRM 문서");
            walk(fs.getRoot(), (flags & 1) != 0, new long[]{0, 0}, 0);
        }
    }
    private static void walk(DirectoryEntry directory, boolean compressed, long[] budget, int depth) throws IOException {
        if (depth > 64) throw new IOException("OLE 깊이 상한 초과");
        for (var entry : directory) {
            if (++budget[1] > 1024) throw new IOException("OLE 항목 상한 초과");
            if (entry instanceof DirectoryEntry child) { walk(child, compressed, budget, depth + 1); continue; }
            var document = (DocumentEntry) entry;
            if (document.getSize() > MAX) throw new IOException("OLE 스트림 상한 초과");
            boolean records = document.getName().equals("DocInfo") || directory.getName().equals("BodyText");
            try (var input = new DocumentInputStream(document)) {
                byte[] bytes = input.readNBytes(MAX + 1);
                if (compressed && (records || directory.getName().equals("Scripts") || directory.getName().equals("BinData"))) {
                    var inflater = new Inflater(true);
                    try (var unpack = new InflaterInputStream(new ByteArrayInputStream(bytes), inflater)) {
                        byte[] expanded = unpack.readNBytes(MAX + 1);
                        if (expanded.length > MAX) throw new IOException("HWP 해제 상한 초과");
                        bytes = expanded;
                    } catch (ZipException e) { if (records) throw e; }
                    finally { inflater.end(); }
                }
                budget[0] += bytes.length;
                if (budget[0] > 2 * 1024 * 1024) throw new IOException("HWP 전체 해제 상한 초과");
                if (records) checkRecords(bytes);
            }
        }
    }
    private static void checkRecords(byte[] bytes) throws IOException {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); int count = 0;
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 4 || ++count > 100000) throw new IOException("HWP 레코드 손상/상한");
            int header = buffer.getInt(), length = header >>> 20;
            if (((header >>> 10) & 1023) > 64) throw new IOException("HWP 레코드 깊이 상한 초과");
            if (length == 4095) { if (buffer.remaining() < 4) throw new IOException("HWP 크기 손상"); length = buffer.getInt(); }
            if (length < 0 || length > buffer.remaining()) throw new IOException("HWP 레코드 크기 손상");
            buffer.position(buffer.position() + length);
        }
    }
}
