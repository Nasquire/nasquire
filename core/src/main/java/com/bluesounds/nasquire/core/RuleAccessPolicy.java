package com.bluesounds.nasquire.core;

import java.util.*;

/** 기본 거부. 가장 긴 접두사, 같은 길이는 DENY 우선인 불변 정책. */
public final class RuleAccessPolicy implements AccessPolicy {
    public static final int MAX_RULES = 4096;
    private final List<AccessRule> rules;
    public RuleAccessPolicy(Collection<AccessRule> rules) {
        if (rules.size() > MAX_RULES) throw new IllegalArgumentException("규칙 상한 4096 초과");
        this.rules = List.copyOf(rules);
    }
    public AccessScope scopeFor(Principal principal, Collection<Source> sources) {
        Objects.requireNonNull(principal);
        var registered = new HashSet<String>();
        sources.forEach(s -> registered.add(s.id()));
        var selected = new HashMap<String, Map<String, AccessRule.Effect>>();
        for (var rule : rules) if (registered.contains(rule.sourceId()) && rule.subject().matches(principal))
            selected.computeIfAbsent(rule.sourceId(), k -> new HashMap<>()).merge(rule.pathPrefix(), rule.effect(),
                    (a, b) -> a == AccessRule.Effect.DENY || b == AccessRule.Effect.DENY ? AccessRule.Effect.DENY : AccessRule.Effect.ALLOW);
        var decisions = new HashMap<String, List<AccessScope.Decision>>();
        selected.forEach((source, paths) -> decisions.put(source, paths.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).map(e -> new AccessScope.Decision(e.getKey(), e.getValue())).toList()));
        return new AccessScope(selected.keySet(), Map.of(), decisions);
    }
}
