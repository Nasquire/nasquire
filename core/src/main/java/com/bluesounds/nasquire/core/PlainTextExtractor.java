package com.bluesounds.nasquire.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.Locale;
import com.ibm.icu.text.CharsetDetector;

public final class PlainTextExtractor implements TextExtractor {
    public boolean supports(Path path) {
        String name = path.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".txt") || name.endsWith(".md");
    }

    public void extract(Path path, PageConsumer consumer, int maxCharacters) throws IOException {
        byte[] sample;
        try (var in = Files.newInputStream(path)) { sample = in.readNBytes(65536); }
        Charset charset = StandardCharsets.UTF_8;
        try {
            var result = charset.newDecoder().decode(ByteBuffer.wrap(sample), CharBuffer.allocate(sample.length), false);
            if (result.isError()) result.throwException();
        }
        catch (CharacterCodingException e) {
            var match = new CharsetDetector().setText(sample).detect();
            if (match == null || match.getConfidence() < 20) throw new IOException("텍스트 인코딩을 인식할 수 없습니다");
            String detected = match.getName();
            try { charset = Charset.forName(detected); }
            catch (IllegalArgumentException ex) { throw new IOException("지원하지 않는 인코딩", ex); }
        }
        try (var reader = new InputStreamReader(Files.newInputStream(path), charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            var text = new StringBuilder();
            char[] buffer = new char[8192];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                if (text.length() + n > maxCharacters) throw new IOException("추출 텍스트 상한 초과");
                text.append(buffer, 0, n);
            }
            consumer.accept(1, text.toString().replace("\uFEFF", ""));
        }
    }
}
