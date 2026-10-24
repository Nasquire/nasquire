package com.bluesounds.nasquire.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.openxml4j.opc.*;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.poifs.filesystem.*;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.hslf.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.eventusermodel.*;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.hssf.eventusermodel.*;
import org.apache.poi.hssf.record.*;
import org.apache.poi.util.XMLHelper;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Excel은 이벤트 방식으로 읽고 나머지 객체 모델은 보수적인 압축 해제 상한으로 보호한다. */
public final class OfficeTextExtractor implements TextExtractor {
    private static final Set<String> TYPES = Set.of("docx", "doc", "pptx", "ppt", "xlsx", "xls", "hwp", "hwpx");
    static {
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxFileCount(1024);
        ZipSecureFile.setMaxEntrySize(8L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(4_000_000);
        HWPFDocument.setMaxRecordLength(8 * 1024 * 1024);
        org.apache.poi.util.IOUtils.setByteArrayMaxOverride(8 * 1024 * 1024);
    }
    private static String extension(Path path) {
        String name = path.getFileName().toString(); return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
    public boolean supports(Path path) { return TYPES.contains(extension(path)); }
    public void extract(Path path, PageConsumer consumer, int maxCharacters) throws IOException {
        extractLocated(path, (order, location, text) -> consumer.accept(order, text), maxCharacters);
    }
    private static final class Limit extends RuntimeException { }
    private static final class Sink {
        final LocatedConsumer consumer; int remaining, order;
        Sink(LocatedConsumer consumer, int max) { this.consumer = consumer; remaining = max; }
        void emit(Location location, String text) throws IOException {
            if (text == null || text.isBlank()) return;
            if (remaining <= 0) throw new Limit();
            if (order >= 4096) throw new IOException("근거 단위 상한 4096 초과");
            String part = text.substring(0, Math.min(remaining, text.length()));
            consumer.accept(++order, location, part); remaining -= part.length();
            if (remaining == 0) throw new Limit();
        }
    }
    public void extractLocated(Path path, LocatedConsumer consumer, int maxCharacters) throws IOException {
        if (!supports(path) || maxCharacters < 1) throw new IOException("형식 또는 문자 상한 오류");
        if (Files.size(path) > 8L * 1024 * 1024) throw new IOException("Office/HWP 파일 상한 8 MiB 초과");
        String ext = extension(path);
        var sink = new Sink(consumer, maxCharacters);
        try {
            if (Set.of("docx", "pptx", "xlsx", "hwpx").contains(ext)) preflightZip(path);
            switch (ext) {
                case "docx" -> docx(path, sink);
                case "doc" -> doc(path, sink);
                case "pptx" -> pptx(path, sink);
                case "ppt" -> ppt(path, sink);
                case "xlsx" -> xlsx(path, sink);
                case "xls" -> xls(path, sink);
                case "hwp" -> hwp(path, sink);
                case "hwpx" -> hwpx(path, sink);
                default -> throw new IOException("지원하지 않는 형식");
            }
        } catch (Limit done) { /* 문자 상한에서 정상 중단한다. */ }
        catch (Exception e) { throw new IOException("문서 손상·암호 또는 안전 상한 초과", e); }
    }
    static void preflightZip(Path path) throws Exception {
        long total = 0; int count = 0;
        var names = new HashSet<String>();
        try (var zip = new ZipSecureFile(path.toFile())) {
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (++count > 1024 || !names.add(entry.getName())) throw new IOException("ZIP 항목 상한/중복");
                if (entry.isDirectory()) continue;
                try (var input = zip.getInputStream(entry)) {
                    byte[] data = input.readNBytes(8 * 1024 * 1024 + 1);
                    total += data.length;
                    if (data.length > 8 * 1024 * 1024 || total > 16 * 1024 * 1024) throw new IOException("ZIP 해제 상한 초과");
                    String name = entry.getName().toLowerCase(Locale.ROOT);
                    boolean xmlBytes = false;
                    for (var charset : List.of(java.nio.charset.StandardCharsets.UTF_8, java.nio.charset.StandardCharsets.UTF_16LE,
                            java.nio.charset.StandardCharsets.UTF_16BE, java.nio.charset.Charset.forName("UTF-32LE"), java.nio.charset.Charset.forName("UTF-32BE"))) {
                        String prefix = new String(data, 0, Math.min(data.length, 256), charset).replace("\ufeff", "").stripLeading();
                        if (prefix.startsWith("<")) { xmlBytes = true; break; }
                    }
                    if (name.endsWith(".xml") || name.endsWith(".rels") || name.endsWith(".hpf") || xmlBytes) {
                        var xml = XMLHelper.newXMLReader();
                        xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                        xml.setContentHandler(new DefaultHandler() {
                            int depth, nodes;
                            public void startElement(String uri, String local, String q, Attributes a) throws SAXException {
                                if (++depth > 64 || ++nodes > 100000) throw new SAXException("XML 구조 상한 초과");
                            }
                            public void endElement(String uri, String local, String q) { depth--; }
                        });
                        xml.setErrorHandler(new DefaultHandler() {
                            public void fatalError(SAXParseException e) throws SAXException { throw e; }
                            public void error(SAXParseException e) throws SAXException { throw e; }
                        });
                        xml.parse(new InputSource(new ByteArrayInputStream(data)));
                    }
                }
            }
        }
    }
    private static void docx(Path path, Sink sink) throws Exception {
        try (var pkg = OPCPackage.open(path.toFile(), PackageAccess.READ); var document = new XWPFDocument(pkg)) {
            int[] number = {0}; String[] heading = {null};
            wordElements(document.getBodyElements(), sink, number, heading);
        }
    }
    private static void wordElements(List<IBodyElement> elements, Sink sink, int[] number, String[] heading) throws IOException {
        for (var element : elements) {
            if (element instanceof XWPFParagraph p) {
                number[0]++;
                if (p.getStyle() != null && p.getStyle().toLowerCase(Locale.ROOT).startsWith("heading")) heading[0] = p.getText().substring(0, Math.min(200, p.getText().length()));
                sink.emit(Location.paragraph(number[0], heading[0]), p.getText() + "\n");
            } else if (element instanceof XWPFTable table) {
                for (var row : table.getRows()) for (var cell : row.getTableCells()) wordElements(cell.getBodyElements(), sink, number, heading);
            }
        }
    }
    private static void doc(Path path, Sink sink) throws Exception {
        try (var fs = new POIFSFileSystem(path.toFile(), true); var document = new HWPFDocument(fs)) {
            var range = document.getRange();
            for (int i = 0; i < range.numParagraphs(); i++) sink.emit(Location.paragraph(i + 1, null), range.getParagraph(i).text());
        }
    }
    private static void pptx(Path path, Sink sink) throws Exception {
        try (var pkg = OPCPackage.open(path.toFile(), PackageAccess.READ); var slides = new XMLSlideShow(pkg)) {
            int number = 0;
            for (var slide : slides.getSlides()) {
                var location = Location.slide(++number);
                for (var shape : slide.getShapes()) slideShape(shape, location, sink);
            }
        }
    }
    private static void slideShape(XSLFShape shape, Location location, Sink sink) throws IOException {
        if (shape instanceof XSLFTextShape text) sink.emit(location, text.getText() + "\n");
        else if (shape instanceof XSLFTable table) {
            for (var row : table.getRows()) for (var cell : row.getCells()) sink.emit(location, cell.getText() + "\n");
        } else if (shape instanceof XSLFGroupShape group) for (var child : group.getShapes()) slideShape(child, location, sink);
    }
    private static void ppt(Path path, Sink sink) throws Exception {
        try (var fs = new POIFSFileSystem(path.toFile(), true); var slides = new HSLFSlideShow(fs)) {
            int number = 0;
            for (var slide : slides.getSlides()) {
                var location = Location.slide(++number);
                for (var shape : slide.getShapes()) legacyShape(shape, location, sink);
            }
        }
    }
    private static void legacyShape(HSLFShape shape, Location location, Sink sink) throws IOException {
        if (shape instanceof HSLFTextShape text) sink.emit(location, text.getText() + "\n");
        else if (shape instanceof HSLFGroupShape group) for (var child : group.getShapes()) legacyShape(child, location, sink);
    }
    private static void xlsx(Path path, Sink sink) throws Exception {
        try (var pkg = OPCPackage.open(path.toFile(), PackageAccess.READ)) {
            var reader = new XSSFReader(pkg); reader.setUseReadOnlySharedStringsTable(true);
            var strings = reader.getSharedStringsTable();
            var sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) try (var input = sheets.next()) {
                String sheet = sheets.getSheetName();
                var xml = XMLHelper.newXMLReader();
                xml.setContentHandler(new XSSFSheetXMLHandler(reader.getStylesTable(), strings, new XSSFSheetXMLHandler.SheetContentsHandler() {
                    final StringBuilder row = new StringBuilder();
                    public void startRow(int number) { row.setLength(0); }
                    public void cell(String ref, String value, org.apache.poi.xssf.usermodel.XSSFComment comment) {
                        if (row.length() < sink.remaining) row.append(value, 0, Math.min(value.length(), sink.remaining - row.length())).append('\t');
                    }
                    public void endRow(int number) {
                        try { sink.emit(Location.row(sheet, number + 1), row + "\n"); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    }
                }, new DataFormatter(Locale.ROOT), false));
                xml.parse(new InputSource(input));
            }
        }
    }
    private static void xls(Path path, Sink sink) throws Exception {
        try (var fs = new POIFSFileSystem(path.toFile(), true)) {
            var request = new HSSFRequest();
            request.addListenerForAllRecords(new HSSFListener() {
                final List<BoundSheetRecord> bounds = new ArrayList<>();
                List<String> names = List.of(); SSTRecord strings; int sheet = -1, formulaRow = -1, records;
                public void processRecord(org.apache.poi.hssf.record.Record record) {
                    if (++records > 100000) throw new IllegalArgumentException("XLS 레코드 상한 초과");
                    if (record instanceof FilePassRecord) throw new IllegalArgumentException("암호 파일");
                    if (record instanceof BoundSheetRecord bound) bounds.add(bound);
                    if (record instanceof SSTRecord sst) strings = sst;
                    if (record instanceof BOFRecord bof && bof.getType() == BOFRecord.TYPE_WORKSHEET) {
                        if (sheet < 0) names = bounds.stream().sorted(Comparator.comparingInt(BoundSheetRecord::getPositionOfBof)).map(BoundSheetRecord::getSheetname).toList();
                        sheet++;
                    }
                    if (sheet < 0 || sheet >= names.size()) return;
                    String value = null; int row = 0;
                    if (record instanceof LabelSSTRecord cell) { row = cell.getRow(); value = strings.getString(cell.getSSTIndex()).getString(); }
                    else if (record instanceof LabelRecord cell) { row = cell.getRow(); value = cell.getValue(); }
                    else if (record instanceof NumberRecord cell) { row = cell.getRow(); value = Double.toString(cell.getValue()); }
                    else if (record instanceof RKRecord cell) { row = cell.getRow(); value = Double.toString(cell.getRKNumber()); }
                    else if (record instanceof BoolErrRecord cell) { row = cell.getRow(); value = cell.isBoolean() ? Boolean.toString(cell.getBooleanValue()) : "#ERROR"; }
                    else if (record instanceof MulRKRecord cells) {
                        for (int i = 0; i < cells.getNumColumns(); i++) try {
                            sink.emit(Location.row(names.get(sheet), cells.getRow() + 1), Double.toString(cells.getRKNumberAt(i)) + "\t");
                        } catch (IOException e) { throw new UncheckedIOException(e); }
                    }
                    else if (record instanceof FormulaRecord cell) {
                        row = cell.getRow();
                        if (cell.hasCachedResultString()) formulaRow = row;
                        else if (!Double.isNaN(cell.getValue())) value = Double.toString(cell.getValue());
                    }
                    else if (record instanceof StringRecord string && formulaRow >= 0) { row = formulaRow; value = string.getString(); formulaRow = -1; }
                    if (value != null) try { sink.emit(Location.row(names.get(sheet), row + 1), value + "\t"); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                }
            });
            new HSSFEventFactory().processWorkbookEvents(request, fs);
        }
    }
    private static void hwp(Path path, Sink sink) throws Exception {
        HwpSafety.check(path);
        try (var input = Files.newInputStream(path)) {
            var file = kr.dogfoot.hwplib.reader.HWPReader.fromInputStream(input);
            int number = 0;
            var heads = new kr.dogfoot.hwplib.tool.textextractor.paraHead.ParaHeadMaker(file);
            for (var section : file.getBodyText().getSectionList()) {
                heads.startSection(section);
                for (var paragraph : section.getParagraphs()) {
                    var text = new StringBuilder();
                    kr.dogfoot.hwplib.tool.textextractor.ForParagraph.extract(paragraph,
                            kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod.AppendControlTextAfterParagraphText, heads, text);
                    sink.emit(Location.paragraph(++number, null), text + "\n");
                }
                heads.endSection();
            }
        }
    }
    private static void hwpx(Path path, Sink sink) throws Exception {
        var file = kr.dogfoot.hwpxlib.reader.HWPXReader.fromFile(path.toFile());
        int number = 0;
        for (var section : file.sectionXMLFileList().items()) for (var paragraph : section.paras()) {
            sink.emit(Location.paragraph(++number, null), kr.dogfoot.hwpxlib.tool.textextractor.TextExtractor.extractFrom(paragraph,
                    kr.dogfoot.hwpxlib.tool.textextractor.TextExtractMethod.AppendControlTextAfterParagraphText, null) + "\n");
        }
    }
}
