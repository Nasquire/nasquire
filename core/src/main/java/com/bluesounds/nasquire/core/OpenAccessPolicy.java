package com.bluesounds.nasquire.core;

import java.util.Collection;
import java.util.stream.Collectors;

/** 개인용/내부용: 등록된 모든 소스를 전체 허용한다. */
public final class OpenAccessPolicy implements AccessPolicy {
    public AccessScope scopeFor(Principal principal, Collection<Source> sources) {
        return AccessScope.sources(sources.stream().map(Source::id).collect(Collectors.toSet()));
    }
}
