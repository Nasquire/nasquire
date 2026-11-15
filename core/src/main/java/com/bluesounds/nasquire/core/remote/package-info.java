/**
 * 임베딩·리랭크·OCR·문서 변환이 함께 쓰는 원격 연결 계층(Spring 비의존).
 * 서버 여러 개를 round_robin으로 나누고 연결 실패·시간 초과·5xx·429만 다음 서버로 넘긴다.
 * 서버별 서킷 브레이커 상태를 제공하며 상태·예외에는 주소·인증값·응답 본문을 넣지 않는다.
 */
package com.bluesounds.nasquire.core.remote;
