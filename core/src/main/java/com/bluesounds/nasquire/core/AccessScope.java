package com.bluesounds.nasquire.core;

import java.text.Normalizer;
import java.util.*;

/** 불변 검색 권한. 빈 소스 집합은 거부한다.
 * 기존 prefixes의 빈 목록은 소스 전체 허용이며, decisions가 있는 소스는 가장 긴 결정(동률 DENY)을 따른다.
 */
public record AccessScope(Set<String> sources, Map<String, List<String>> prefixes,
                          Map<String, List<Decision>> decisions) {
    /** 경로별 결정. 빈 접두사는 소스 전체이며 가장 긴 접두사가 우선한다. */
    public record Decision(String pathPrefix, AccessRule.Effect effect) {
        public Decision {
            Objects.requireNonNull(pathPrefix);
            Objects.requireNonNull(effect);
            if (!pathPrefix.isEmpty()) validatePath(pathPrefix);
        }
    }
    public AccessScope(Set<String> sources, Map<String, List<String>> prefixes) {
        this(sources, prefixes, Map.of());
    }
    static final int MAX_PREFIXES = 1024;
    /** 허용 영역과 그 영역에서 제외할 하위 접두사. 저장소 필터도 이 표현을 공유한다. */
    record Region(String source, String prefix, List<String> excluded) { }
    List<Region> regions() {
        var result = new ArrayList<Region>();
        for (String source : sources) {
            var rules = decisions.get(source);
            if (rules == null) {
                var paths = prefixes.getOrDefault(source, List.of());
                for (String path : paths.isEmpty() ? List.of("") : paths)
                    result.add(new Region(source, path, List.of()));
            } else for (var rule : rules) if (rule.effect() == AccessRule.Effect.ALLOW) {
                var excluded = rules.stream().filter(d -> d.effect() == AccessRule.Effect.DENY
                        && d.pathPrefix().length() >= rule.pathPrefix().length()
                        && contains(rule.pathPrefix(), d.pathPrefix())).map(Decision::pathPrefix).toList();
                result.add(new Region(source, rule.pathPrefix(), excluded));
            }
        }
        return result;
    }
    static boolean contains(String prefix, String path) {
        return prefix.isEmpty() || path.equals(prefix) || path.startsWith(prefix + "/");
    }
    public boolean restricted() { return !decisions.isEmpty() || prefixes.values().stream().anyMatch(p -> !p.isEmpty()); }

    public AccessScope {
        sources = Set.copyOf(sources);
        var copy = new HashMap<String, List<String>>();
        for (var entry : prefixes.entrySet()) {
            if (!sources.contains(entry.getKey())) throw new IllegalArgumentException("허용되지 않은 소스");
            entry.getValue().forEach(AccessScope::validatePath);
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        prefixes = Map.copyOf(copy);
        var compiled = new HashMap<String, List<Decision>>();
        for (var entry : decisions.entrySet()) {
            if (!sources.contains(entry.getKey())) throw new IllegalArgumentException("허용되지 않은 소스");
            compiled.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        decisions = Map.copyOf(compiled);
        long count = prefixes.values().stream().mapToLong(List::size).sum()
                + decisions.values().stream().mapToLong(List::size).sum();
        if (count > MAX_PREFIXES) throw new IllegalArgumentException("접두사 상한 1024 초과");
    }
    public static AccessScope sources(Set<String> sources) { return new AccessScope(sources, Map.of()); }
    public static void validatePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\")
                || path.matches("^[A-Za-z]:.*") || !Normalizer.isNormalized(path, Normalizer.Form.NFC)
                || path.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("상대 경로 오류");
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("상대 경로 오류");
    }
    /** key는 기존 색인의 sourceId:relativePath 형식이다. 잘못된 결과는 거부한다. */
    public boolean allows(String source, String key) {
        if (!sources.contains(source) || !key.startsWith(source + ":")) return false;
        String path = key.substring(source.length() + 1);
        try { validatePath(path); } catch (IllegalArgumentException e) { return false; }
        if (decisions.containsKey(source)) {
            Decision winner = null;
            for (var decision : decisions.get(source)) if (contains(decision.pathPrefix(), path)
                    && (winner == null || decision.pathPrefix().length() > winner.pathPrefix().length()
                    || decision.pathPrefix().length() == winner.pathPrefix().length() && decision.effect() == AccessRule.Effect.DENY))
                winner = decision;
            return winner != null && winner.effect() == AccessRule.Effect.ALLOW;
        }
        var allowed = prefixes.getOrDefault(source, List.of());
        return allowed.isEmpty() || allowed.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }
}
