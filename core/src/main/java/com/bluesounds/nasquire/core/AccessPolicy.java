package com.bluesounds.nasquire.core;

import java.util.Collection;

/** 조회 시점 정책. 인증과 멤버십 조회는 호출 앱의 책임이다. */
public interface AccessPolicy {
    AccessScope scopeFor(Principal principal, Collection<Source> sources);
    static AccessPolicy open() { return new OpenAccessPolicy(); }
}
