package com.bluesounds.nasquire.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/** 외부 문서를 복제하지 않고 각 형식의 최소 문서를 직접 작성한다. */
public final class OfficeSamples {
    public static void generate(Path folder) throws Exception {
        Files.createDirectories(folder);
        try (var document = new XWPFDocument()) {
            var heading = document.createParagraph(); heading.setStyle("Heading1"); heading.createRun().setText("Original heading");
            document.createParagraph().createRun().setText("formatdocx 문서 형식 확장 검증");
            try (var out = Files.newOutputStream(folder.resolve("sample.docx"))) { document.write(out); }
        }
        doc(folder.resolve("sample.doc"), "formatdoc Original Word document\rSecond paragraph\r");
        try (var slides = new XMLSlideShow()) {
            slides.createSlide().createTextBox().setText("Original first slide");
            slides.createSlide().createTextBox().setText("formatpptx 슬라이드 검증");
            try (var out = Files.newOutputStream(folder.resolve("sample.pptx"))) { slides.write(out); }
        }
        try (var slides = new HSLFSlideShow()) {
            slides.createSlide().createTextBox().setText("Original first slide");
            slides.createSlide().createTextBox().setText("formatppt 슬라이드 검증");
            try (var out = Files.newOutputStream(folder.resolve("sample.ppt"))) { slides.write(out); }
        }
        workbook(new SXSSFWorkbook(10), folder.resolve("sample.xlsx"), "formatxlsx");
        workbook(new HSSFWorkbook(), folder.resolve("sample.xls"), "formatxls");
        var hwp = kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker.make();
        hwp.getBodyText().getSectionList().getFirst().getParagraph(0).getText().addString("formathwp 한글 문단 검증");
        kr.dogfoot.hwplib.writer.HWPWriter.toFile(hwp, folder.resolve("sample.hwp").toString());
        var hwpx = kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker.make();
        hwpx.sectionXMLFileList().get(0).getPara(0).addNewRun().charPrIDRefAnd("0").addNewT().addText("formathwpx 한글 XML 문단 검증");
        kr.dogfoot.hwpxlib.writer.HWPXWriter.toFilepath(hwpx, folder.resolve("sample.hwpx").toString());
    }
    private static void workbook(Workbook workbook, Path path, String keyword) throws Exception {
        try (workbook) {
            workbook.createSheet("First").createRow(0).createCell(0).setCellValue("Original first sheet");
            var sheet = workbook.createSheet("자료");
            sheet.createRow(4).createCell(0).setCellValue(keyword + " 시트 행 검증");
            sheet.createRow(6).createCell(1).setCellValue(42.5);
            try (var out = Files.newOutputStream(path)) { workbook.write(out); }
        }
    }
    /** Word 97 FIB와 단일 UTF-16 piece table을 직접 작성한다. 이미지·매크로는 없다. */
    static void doc(Path path, String text) throws IOException {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_16LE);
        var word = ByteBuffer.allocate(1024 + encoded.length).order(ByteOrder.LITTLE_ENDIAN);
        word.putShort(0, (short)0xa5ec).putShort(2, (short)0xc1).putShort(6, (short)0x409);
        word.putShort(10, (short)0x4).putInt(24, 1024).putInt(28, word.capacity());
        word.putShort(32, (short)14).putShort(62, (short)22).putInt(64, word.capacity()).putInt(76, text.length());
        word.putShort(152, (short)93);
        var table = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        table.putShort(64, (short)18); // 빈 stylesheet
        field(word, 1, 64, 20); field(word, 15, 96, 4); field(word, 31, 128, 500);
        // Plcfsed: CP 0..끝과 속성 없는 section descriptor
        table.putInt(640, 0).putInt(644, text.length()).putInt(650, -1);
        field(word, 6, 640, 20);
        table.put(768, (byte)2).putInt(769, 16).putInt(773, 0).putInt(777, text.length());
        table.putInt(783, 1024); field(word, 33, 768, 21);
        word.position(1024); word.put(encoded);
        try (var fs = new POIFSFileSystem(); var out = Files.newOutputStream(path)) {
            fs.createDocument(new ByteArrayInputStream(word.array()), "WordDocument");
            fs.createDocument(new ByteArrayInputStream(table.array()), "0Table"); fs.writeFilesystem(out);
        }
    }
    private static void field(ByteBuffer word, int index, int offset, int length) {
        word.putInt(154 + index * 8, offset).putInt(158 + index * 8, length);
    }
}
