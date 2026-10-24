package com.bluesounds.nasquire.core;

import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;

class CoreTest {
    @TempDir Path temp;
    private static final List<TextExtractor> EXTRACTORS = List.of(new PlainTextExtractor(), new PdfTextExtractor());

    @Test void extractsPagesAndEncodingWithLimits() throws Exception {
        Path text = temp.resolve("한글.md");
        Files.writeString(text, "한국어 문서 검색과 English Search");
        var pages = new ArrayList<String>();
        new PlainTextExtractor().extract(text, (p, s) -> pages.add(s), 1000);
        assertTrue(pages.getFirst().contains("한국어"));
        Files.write(text, "한글 문서 검색 테스트입니다. 우리나라 문서를 검색합니다. 한국어 인코딩 확인입니다.".repeat(20).getBytes(Charset.forName("EUC-KR")));
        pages.clear();
        new PlainTextExtractor().extract(text, (p, s) -> pages.add(s), 2000);
        assertTrue(pages.getFirst().contains("한국어"));
        assertThrows(java.io.IOException.class, () -> new PlainTextExtractor().extract(text, (p, s) -> {}, 10));
        Path pdf = temp.resolve("sample.pdf");
        SampleDocuments.pdf(pdf, "First page", "Second page");
        pages.clear();
        new PdfTextExtractor().extract(pdf, (p, s) -> pages.add(p + ":" + s), 1000);
        assertEquals(2, pages.size()); assertTrue(pages.get(1).contains("2:Second page"));
        assertThrows(java.io.IOException.class, () -> new PdfTextExtractor().extract(pdf, (p, s) -> {}, 5));
    }

    @Test void scansStableChangesDeletionSymlinksGlobsAndNfc() throws Exception {
        Path root = Files.createDirectory(temp.resolve("sources"));
        Files.writeString(root.resolve("한글.txt"), "한국어 검색 문서 astronomy");
        Files.writeString(root.resolve("~$secret.txt"), "secret");
        Files.writeString(root.resolve("ignored.tmp"), "secret");
        Files.createDirectory(root.resolve("@eaDir"));
        Files.writeString(root.resolve("@eaDir/hidden.txt"), "secret");
        Files.writeString(root.resolve("private.txt"), "secret");
        Files.writeString(root.resolve("large.txt"), "x".repeat(5000));
        Path external = Files.writeString(temp.resolve("external.txt"), "secret");
        Files.createSymbolicLink(root.resolve("escape.txt"), external);
        Files.createSymbolicLink(root.resolve("loop"), root);
        Files.createSymbolicLink(root.resolve("inside.txt"), root.resolve("한글.txt"));
        var source = new Source("one", "자료", root, List.of("**/*.txt"), List.of("private.txt", "inside.txt"));
        try (var index = new SearchIndex(temp.resolve("index"));
             var service = new IndexingService(index, 4000, 5000, 60, EXTRACTORS)) {
            service.scan(List.of(source));
            assertTrue(index.search("한국어", Set.of("one"), 10).isEmpty());
            Thread.sleep(90);
            service.scan(List.of(source));
            var hits = index.search("한국어", Set.of("one"), 10);
            assertEquals(1, hits.size()); assertEquals("one:한글.txt", hits.getFirst().path());
            assertFalse(index.search("ASTRONOMY", Set.of("one"), 10).isEmpty());
            assertTrue(index.search("secret", Set.of("one"), 10).isEmpty());
            assertTrue(index.search("한국어", Set.of(), 10).isEmpty());
            assertTrue(index.search("한국어", Set.of("other"), 10).isEmpty());
            assertEquals(1, service.status("one").indexed());
            Files.writeString(root.resolve("한글.txt"), "변경된 문서 새로운 용어 galaxy");
            service.scan(List.of(source)); Thread.sleep(90); service.scan(List.of(source));
            assertTrue(index.search("한국어", Set.of("one"), 10).isEmpty());
            assertEquals(1, index.search("galaxy", Set.of("one"), 10).size());
            Files.delete(root.resolve("한글.txt")); service.scan(List.of(source));
            assertTrue(index.search("galaxy", Set.of("one"), 10).isEmpty());
        }
    }

    @Test void persistsChunksAndRemovesSource() throws Exception {
        Path folder = temp.resolve("index");
        var source = new Source("s", "sample", temp, List.of(), List.of());
        try (var index = new SearchIndex(folder)) {
            index.replace(source, "doc.pdf", 42, List.of(new SearchIndex.Chunk(1, 0, "한국어 검색"), new SearchIndex.Chunk(2, 6, "English indexing")));
            index.refresh();
        }
        try (var index = new SearchIndex(folder)) {
            assertEquals(2, index.document("s:doc.pdf", Set.of("s")).size());
            assertEquals(2, index.search("English", Set.of("s"), 10).getFirst().page());
            assertThrows(IllegalArgumentException.class, () -> index.search("x", Set.of("s"), 101));
            index.deleteSource("s"); index.refresh();
            assertTrue(index.search("English", Set.of("s"), 10).isEmpty());
        }
    }
}
