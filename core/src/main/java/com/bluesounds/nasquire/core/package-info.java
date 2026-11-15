/**
 * Nasquire 공개 라이브러리 API. 진입점은 {@link com.bluesounds.nasquire.core.NasquireCore}이며
 * Source, SearchIndex, IndexingService, TextExtractor, Providers, VectorStore는 저수준 확장 API다.
 * remote 하위 패키지는 원격 서버 묶음(분배·페일오버·서킷 브레이커) 공개 API다. internal 하위 패키지는 호환성 보장 대상이 아니다. 외부 앱의 인증 결과는 AccessScope로 전달한다.
 */
package com.bluesounds.nasquire.core;
