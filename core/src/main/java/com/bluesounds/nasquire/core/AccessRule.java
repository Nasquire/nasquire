package com.bluesounds.nasquire.core;

import java.util.Objects;

/** 소스와 상대 경로에 적용할 불변 규칙. */
public record AccessRule(String sourceId, String pathPrefix, Subject subject, Effect effect) {
    public enum Effect { ALLOW, DENY }
    /** user:id, group:id 또는 everyone. */
    public record Subject(String value) {
        public Subject {
            Objects.requireNonNull(value);
            if (!value.equals("everyone")) {
                int colon = value.indexOf(':');
                if (colon < 0 || !(value.startsWith("user:") || value.startsWith("group:")))
                    throw new IllegalArgumentException("주체 형식 오류");
                value = value.substring(0, colon + 1) + Principal.normalize(value.substring(colon + 1));
            }
        }
        public static Subject user(String id) { return new Subject("user:" + id); }
        public static Subject group(String id) { return new Subject("group:" + id); }
        public static Subject everyone() { return new Subject("everyone"); }
        boolean matches(Principal p) {
            return value.equals("everyone") || value.equals("user:" + p.userId())
                    || value.startsWith("group:") && p.groupIds().contains(value.substring(6));
        }
    }
    public AccessRule {
        sourceId = Principal.normalize(sourceId);
        Objects.requireNonNull(pathPrefix);
        if (!pathPrefix.isEmpty()) AccessScope.validatePath(pathPrefix);
        Objects.requireNonNull(subject); Objects.requireNonNull(effect);
    }
}
