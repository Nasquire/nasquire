package com.bluesounds.nasquire.app;

import java.nio.charset.StandardCharsets;

/** 관리자 비밀번호를 새로 저장할 때만 적용하는 공통 정책. */
final class PasswordPolicy {
    static final String MESSAGE = "관리자 비밀번호는 8자 이상, UTF-8 72바이트 이하이며 영문자·숫자·출력 가능한 ASCII 문장부호를 각각 포함해야 합니다(대소문자 각각 필수 아님) / Administrator password must be at least 8 characters, at most 72 UTF-8 bytes, and include an ASCII letter, digit and printable ASCII punctuation (both letter cases are not required)";

    static final class Invalid extends IllegalArgumentException {
        Invalid() { super(MESSAGE); }
    }

    static void validate(String password) {
        if (password == null || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72
                || !password.matches("(?s).*[A-Za-z].*") || !password.matches("(?s).*[0-9].*")
                || !password.matches("(?s).*[\\x21-\\x2F\\x3A-\\x40\\x5B-\\x60\\x7B-\\x7E].*"))
            throw new Invalid();
    }
}
