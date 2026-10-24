package com.bluesounds.nasquire.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.poifs.crypt.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OfficeExtractorTest {
    @TempDir Path folder;
    final OfficeTextExtractor extractor = new OfficeTextExtractor();
    record Part(Location location, String text) { }
    @Test void generatedFormatsAndLocationsRoundTripThroughIndex() throws Exception {
        OfficeSamples.generate(folder);
        try (var index = new SearchIndex(folder.resolve("index"))) {
            var source = new Source("s", "samples", folder, List.of(), List.of());
            var indexing = new IndexingService(index, 32 * 1024 * 1024, 10000, 0, List.of(extractor));
            try (indexing) {
                indexing.scan(List.of(source));
                assertEquals(8, indexing.status("s").indexed(), indexing.status("s").toString());
                assertEquals(0, indexing.status("s").errors());
                for (String ext : List.of("docx", "doc", "pptx", "ppt", "xlsx", "xls", "hwp", "hwpx")) {
                    var parts = new ArrayList<Part>();
                    extractor.extractLocated(folder.resolve("sample." + ext), (n, location, text) -> parts.add(new Part(location, text)), 10000);
                    assertTrue(parts.stream().anyMatch(p -> p.text.contains("format" + ext)), ext + ": " + parts);
                    var hits = index.search("format" + ext, Set.of("s"), 10);
                    assertFalse(hits.isEmpty(), ext);
                    var location = hits.getFirst().location();
                    if (ext.startsWith("ppt")) assertEquals(2, location.slide());
                    else if (ext.startsWith("xls")) { assertEquals("자료", location.sheet()); assertEquals(5, location.rowFrom()); assertEquals(5, location.rowTo()); }
                    else assertNotNull(location.paragraph());
                    assertEquals(location, index.document("s:sample." + ext, Set.of("s")).stream().filter(h -> h.text().contains("format" + ext)).findFirst().orElseThrow().location());
                    int[] characters = {0};
                    extractor.extract(folder.resolve("sample." + ext), (n, text) -> characters[0] += text.length(), 7);
                    assertEquals(7, characters[0], ext);
                }
            }
        }
    }
    @Test void corruptEncryptedOversizeAndZipBombFailWithoutKillingIndexer() throws Exception {
        OfficeSamples.generate(folder);
        for (String ext : List.of("docx", "doc", "pptx", "ppt", "xlsx", "xls", "hwp", "hwpx")) {
            Path corrupt = Files.writeString(folder.resolve("corrupt." + ext), "damaged original sample");
            assertThrows(IOException.class, () -> extractor.extract(corrupt, (n, text) -> {}, 10000), ext);
        }
        for (String ext : List.of("docx", "pptx", "xlsx")) try (var fs = new POIFSFileSystem()) {
            var info = new EncryptionInfo(EncryptionMode.agile);
            var encryptor = info.getEncryptor(); encryptor.confirmPassword("sample-password");
            try (var out = encryptor.getDataStream(fs)) { out.write(Files.readAllBytes(folder.resolve("sample." + ext))); }
            try (var out = Files.newOutputStream(folder.resolve("encrypted." + ext))) { fs.writeFilesystem(out); }
        }
        for (String ext : List.of("docx", "pptx", "xlsx"))
            assertThrows(IOException.class, () -> extractor.extract(folder.resolve("encrypted." + ext), (n, text) -> {}, 10000));
        org.apache.poi.hssf.record.crypto.Biff8EncryptionKey.setCurrentUserPassword("sample-password");
        try (var workbook = new org.apache.poi.hssf.usermodel.HSSFWorkbook(); var output = Files.newOutputStream(folder.resolve("encrypted.xls"))) {
            workbook.createSheet("Protected").createRow(0).createCell(0).setCellValue("Confidential test data");
            workbook.write(output);
        } finally { org.apache.poi.hssf.record.crypto.Biff8EncryptionKey.setCurrentUserPassword(null); }
        assertThrows(IOException.class, () -> extractor.extract(folder.resolve("encrypted.xls"), (n, text) -> {}, 10000));
        try (var fs = new POIFSFileSystem(folder.resolve("sample.hwp").toFile(), true); var target = new POIFSFileSystem()) {
            byte[] header; try (var in = fs.createDocumentInputStream("FileHeader")) { header = in.readAllBytes(); }
            header[36] |= 2; target.createDocument(new ByteArrayInputStream(header), "FileHeader");
            try (var out = Files.newOutputStream(folder.resolve("encrypted.hwp"))) { target.writeFilesystem(out); }
        }
        assertThrows(IOException.class, () -> extractor.extract(folder.resolve("encrypted.hwp"), (n, text) -> {}, 10000));
        for (String ext : List.of("docx", "pptx", "xlsx", "hwpx")) {
            Path bomb = folder.resolve("bomb." + ext);
            try (var zip = new ZipOutputStream(Files.newOutputStream(bomb))) {
                zip.putNextEntry(new ZipEntry("bomb.bin")); zip.write(new byte[2 * 1024 * 1024]); zip.closeEntry();
            }
            assertThrows(IOException.class, () -> extractor.extract(bomb, (n, text) -> {}, 10000), ext);
        }
        Path many = folder.resolve("many.docx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(many))) {
            for (int i = 0; i < 1025; i++) { zip.putNextEntry(new ZipEntry("entry" + i)); zip.closeEntry(); }
        }
        assertThrows(IOException.class, () -> extractor.extract(many, (n, text) -> {}, 10000));
        Path xxe = folder.resolve("xxe.hwpx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(xxe))) {
            zip.putNextEntry(new ZipEntry("Contents/section0.xml"));
            zip.write("<!DOCTYPE x [<!ENTITY ext SYSTEM 'file:///nonexistent'>]><x>&ext;</x>".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        assertThrows(IOException.class, () -> extractor.extract(xxe, (n, text) -> {}, 10000));
        Path large = folder.resolve("large.ppt");
        try (var channel = java.nio.channels.FileChannel.open(large, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(8 * 1024 * 1024); channel.write(java.nio.ByteBuffer.wrap(new byte[1]));
        }
        assertThrows(IOException.class, () -> extractor.extract(large, (n, text) -> {}, 10000));
        try (var index = new SearchIndex(folder.resolve("index")); var indexing = new IndexingService(index, 32 * 1024 * 1024, 10000, 0, List.of(extractor))) {
            indexing.scan(List.of(new Source("s", "samples", folder, List.of(), List.of())));
            assertTrue(indexing.status("s").errors() >= 16, indexing.status("s").toString());
            assertEquals(8, indexing.status("s").indexed(), indexing.status("s").toString());
            assertFalse(index.search("formatdocx", Set.of("s"), 10).isEmpty());
        }
    }
}
