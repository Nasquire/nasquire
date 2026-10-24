package com.bluesounds.nasquire.app;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238: SHA-1, 6자리, 30초. */
final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    static String secret() {
        byte[] bytes = new byte[20]; new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(); int buffer = 0, bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 255); bits += 8;
            while (bits >= 5) { bits -= 5; result.append(ALPHABET.charAt((buffer >>> bits) & 31)); }
        }
        return result.toString();
    }
    static String code(String secret, long step) {
        try {
            byte[] key = new byte[secret.length() * 5 / 8]; int buffer = 0, bits = 0, offset = 0;
            for (char c : secret.toCharArray()) {
                int value = ALPHABET.indexOf(c); if (value < 0) throw new IllegalArgumentException("비밀 형식 오류");
                buffer = (buffer << 5) | value; bits += 5;
                if (bits >= 8) { bits -= 8; key[offset++] = (byte)(buffer >>> bits); }
            }
            Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int at = hash[19] & 15;
            int binary = ByteBuffer.wrap(hash, at, 4).getInt() & 0x7fffffff;
            return String.format(java.util.Locale.ROOT, "%06d", binary % 1000000);
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    static long match(String secret, String input, long now, long last) {
        if (input == null || !input.matches("[0-9]{6}")) return -1;
        long current = now / 30000;
        for (long step = current + 1; step >= current - 1; step--)
            if (step > last && java.security.MessageDigest.isEqual(code(secret, step).getBytes(java.nio.charset.StandardCharsets.US_ASCII), input.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) return step;
        return -1;
    }
}
