# 1단계 검증 기록

검증일: 2026-10-24. Java 25, Spring Boot 4.1.2, Gradle 9.8.1, Linux. 외부 저작물이나 운영 자료를 사용하지 않았다.

## 실행 결과

| 명령 | 종료 코드 | 결과 |
| --- | --- | --- |
| `./gradlew build -q` | 0 | React TypeScript/Vite, core/app 컴파일·테스트·실행 JAR 생성 통과 |
| `./gradlew :core:generateSamples -q` | 0 | 자체 작성 Markdown 20개, 2쪽 PDF 20개 생성 |
| `VERIFY_PORT=8116 python3 scripts/verify-runtime.py` | 0 | 새 데이터 폴더의 bootRun·curl·RSS 실측·서버 종료 통과 |
| `git diff --check` | 0 | 공백 오류 없음 |

자동 테스트는 core 3개, app 실제 HTTP 통합 테스트 1개(여러 보안/도구 흐름 포함), 실패/건너뜀 0개다. 세부 항목:

- UTF-8·한국어 EUC-KR 추정·텍스트 상한, PDF 쪽별 추출·상한.
- 한국어/영문 검색, BM25 결과·쪽·NFC 경로, 빈/다른 소스 범위 차단, 색인 영속성.
- 안정화 전 미게시, 변경·삭제 반영, 외부 파일 링크·디렉터리 링크·기본 제외·사용자 glob·크기 상한.
- 첫 관리자 설정, 중복 설정 거부, CSRF 누락 거부, 로그인·로그아웃.
- 토큰 원문/해시 비노출, 소스 제한, 관리 API/쓰기 차단, 잘못된 토큰·만료·폐기 거부.
- MCP Bearer 필수(세션 단독 거부), 허용하지 않은 Origin 거부, initialize·tools/list·search_documents·get_document, 범위/접근 오류와 감사 기록.

## curl 직접 확인

`verify-runtime.py`는 `./gradlew :app:bootRun --no-daemon -q`로 애플리케이션을 실행하고 `smoke.py`가 curl 프로세스를 호출한다. 인증값은 curl 표준 입력으로 전달하며 보고서에는 기록하지 않는다.

```text
PASS static UI bundle, icon and license resources
PASS setup, CSRF protection, administrator login
PASS 40 documents indexed: 20 Markdown, 20 two-page PDF
PASS Korean query: 80 chunks; English PDF query: 20 page-2 results
PASS read-only token, MCP initialize/tools/list/search_documents/get_document
PASS 30 additional searches, token revocation, audit log
PASS logout invalidates administrator session
PEAK_APPLICATION_RSS_KIB=233884
PEAK_APPLICATION_RSS_MIB=228.4
```

정적 관리 화면, 아이콘, 라이선스 자산은 실행 JAR에 포함된다. 테스트용 개발 서버는 종료했고 포트 반환을 확인했다.

## 메모리

`-Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:MaxDirectMemorySize=32m`로 실행했다. 50ms 간격 `/proc/<application-pid>/status`의 VmRSS를 측정했으며, Gradle·Node는 제외했다.

시작 중 최대 165724 KiB(161.8 MiB), 문서 40개 색인/검색 및 MCP 요청 중 최대 233884 KiB(228.4 MiB). 이번 자료와 부하에서는 Spring Boot를 유지할 수 있다고 판단했다.

**실제 512 MiB 컨테이너 제한 검증은 아니다.** cgroup 파일 캐시·컨테이너 전체 메모리, 대형 PDF, 많은 파일의 스냅샷, 동시 부하까지 포함한 후속 측정이 필요하다. compose에 512m 제한 예시를 제공했다.

## 실패·생략 기록

- 최초 빌드 종료 코드 1: npm이 쓰기 제한된 기본 홈 캐시에 기록하려다 실패. 프로젝트 `frontend/build/npm-cache`로 바꾼 뒤 빌드 통과.
- 최초 RSS 수집은 격리된 프로세스 네임스페이스의 다른 프로세스를 측정해 무효였다. 그 수치는 사용하지 않았다. 서버 실행·PID 식별·샘플링을 같은 검증 프로세스 아래 수행하는 재현 스크립트로 수정해 최종 수치를 얻었다.
- 첫 런타임 재실행은 사용 중인 포트에 바인딩하여 종료 코드 1. 앞선 개발 실행을 종료하고 빈 포트에서 재실행했다. 최종 검증 후 서버 포트는 닫혔다.
- 마지막 정적 자산 포함 재검증은 종료 코드 1: 준비 상태가 검색자 갱신보다 먼저 공개되어 즉시 검색 결과가 비는 경쟁 조건을 발견했다. 인덱스 갱신 후 상태를 공개하도록 수정하고 빌드·전체 흐름을 다시 검증했다.
- Docker 명령이 없어 이미지 빌드·512 MiB cgroup·PUID/PGID 실제 동작·다중 아키텍처 실행을 생략했다.
- 브라우저 자동화 도구가 없어 화면의 시각/접근성 검증을 생략했다. TypeScript·프로덕션 번들과 HTTP 정적 자산은 검증했다.
- TOTP는 선택 범위로 이번 단계에서 생략했으며 ROADMAP 후속 항목에 남겼다.

## 아티팩트

재현 시 생성되는 다음 경로는 git에서 무시된다. 원시 실행/테스트 로그에는 실행 환경의 경로 등이 포함될 수 있으므로 공개 커밋에 넣지 않는다.

- `app/build/libs/app-0.1.0.jar`: 프런트엔드를 포함한 실행 JAR.
- `core/build/reports/tests/test/index.html`, `app/build/reports/tests/test/index.html`: 테스트 보고서.
- `build/samples`: 자체 작성 검증 문서.
- `build/verification/measurement.json`: 환경 식별값 없는 RSS 요약.
- `build/verification/smoke.txt`: 인증값 없는 curl 검증 결과.
- `build/verification/bootrun.log`: 로컬 원시 실행 로그(공개 금지).
- `build/verification/runtime-licenses.json`, `build/verification/frontend-licenses.json`: 이번 실행의 의존성 라이선스 점검 자료(추가 점검 작업으로 생성).
