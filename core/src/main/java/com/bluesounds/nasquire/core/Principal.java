package com.bluesounds.nasquire.core;

import java.text.Normalizer;
import java.util.*;

/** 호출 앱이 인증한 사용자와 현재 그룹. core는 멤버십을 저장하지 않는다. */
public record Principal(String userId, Set<String> groupIds) {
    public Principal {
        userId = normalize(userId);
        if (groupIds.size() > 256) throw new IllegalArgumentException("그룹 상한 256 초과");
        var normalized = new HashSet<String>();
        for (String id : groupIds) normalized.add(normalize(id));
        groupIds = Set.copyOf(normalized);
    }
    static String normalize(String value) {
        Objects.requireNonNull(value);
        if (value.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))
            throw new IllegalArgumentException("식별자 제어 문자");
        String id = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        if (id.isEmpty() || id.length() > 256) throw new IllegalArgumentException("식별자 길이 오류 (1..256)");
        return id;
    }
    public static Principal owner() { return new Principal("owner", Set.of()); }
}
