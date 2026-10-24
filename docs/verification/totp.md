# 관리자 TOTP·오프라인 복구 검증

기준 커밋 `f89692f`, 검증일 2026-10-24. 기본 꺼짐인 관리자 TOTP를 구현했다. 신규 라이브러리는 없고 LICENSE·NOTICE·LICENSE-EXCEPTIONS는 변경하지 않았다. MCP OAuth는 구현하지 않았다.

## 구현·판단

- JDK HmacSHA1, RFC 6238의 6자리·30초·±1단계. 160비트 Base32 비밀을 관리자 세션+헤더 CSRF 등록 응답에만 표시한다. QR 대신 URI/비밀을 제공한다. 등록 대기는 암호화된 세션 속성으로 10분간 보관하며 한 번 확인해야 DB에 활성화한다.
- 비밀은 기존 `embedding.key`와 AES-256-GCM으로 DB에 저장한다. 등록 응답 이후 상태 API·감사 로그에 비밀을 반환하지 않는다. 활성화 때 표시하는 160비트 복구 코드 10개는 DB에 SHA-256만 저장하고 조건부 DELETE로 한 번만 소모한다.
- 비밀번호 승인 후에는 5분간 권한 없는 대기 세션만 유지한다. Spring의 성공 핸들러 이전 세션 저장 단계에서도 ADMIN 저장을 막는다. 코드 승인 시 ADMIN을 부여하고 세션 ID와 CSRF를 교체한다. IP/계정 실패 지연·잠금은 비밀번호 승인만으로 초기화하지 않고 최종 승인 후 초기화한다.
- 마지막 승인 TOTP 단계를 DB에 저장하고 CAS UPDATE로 이전/동일 단계 재사용을 거부한다. 미래 +1단계를 사용하면 서버 시간이 이를 지나갈 때까지 새 코드 승인이 지연될 수 있다. 인증 앱 코드는 활성화·로그인·해제에서 같은 재생 기준을 공유한다.
- 해제에는 현재 비밀번호와 사용하지 않은 TOTP가 모두 필요하다. 복구 코드는 로그인에만 쓰며 해제에는 쓸 수 없다. 등록/해제는 기존에 인증된 다른 세션을 종료하지 않는다. 침해 우려 시 앱 종료 후 오프라인 복구·재시작으로 기존 메모리 세션을 없앤다.
- `--recover-admin --data=/data`는 Spring/웹 컨텍스트를 만들지 않고 기존 H2 파일을 열어 비밀번호 재설정·TOTP/복구 코드 삭제·감사를 트랜잭션 처리한다. 비밀번호는 숨김 콘솔 또는 표준 입력으로 받는다. DB 파일 배타 잠금이 실행 중인 별도 프로세스의 복구를 거부한다. API 토큰과 기존 관리자 이름은 유지한다. 절차는 README에 기록했다.
- React 한/영 로그인 코드 단계와 보안 화면(등록·확인·복구 코드 최초 표시·해제)을 추가했다. 의존성을 추가하지 않았다.

## 자동 검증

| 명령 | 종료 코드 | 결과 |
| --- | --- | --- |
| `./gradlew :app:compileJava -q` | 0 | 서버 코드 컴파일 |
| `./gradlew :app:test -q` (최초) | 1 | 8개 중 복구 테스트 1개 실패. 없는 폴더에 대해 IllegalArgumentException을 기대했지만 실제는 NoSuchFileException. 기대 예외 수정 |
| `./gradlew build -q` (최종) | 0 | core 11개·app 10개, TypeScript·Vite·패키징 통과 |
| `python3 scripts/verify-admin-recovery.py` | 0 | 실제 실행 JAR로 별도 JVM DB 잠금 거부·비밀번호 재설정·TOTP/복구 코드 삭제·감사·잘못된 인자 순서 거부. 웹 서버 없이 종료 |
| `VERIFY_PORT=8121 python3 scripts/verify-runtime.py` | 0 | 48문서·16 MiB 업로드·공유·검색·TOTP 등록/확인/로그인/복구 로그인/해제·대기 세션 거부·Bearer/MCP 회귀. 애플리케이션/bootRun 종료와 포트 반환 확인 |
| `git diff --check` | 0 | 공백 오류 없음 |

HTTP 통합 테스트는 CSRF 없는 등록/코드 승인 거부, 잘못된 코드·동일 단계 재사용 거부, 실패 뒤 429 제한, 대기 세션의 sources/tokens/embeddings/totp/search 접근 거부, 최종 승인 후 세션 ID 교체, 복구 코드 재사용 거부와 잔여 개수, 잘못된 비밀번호·복구 코드·잘못된 TOTP로 해제 거부, 올바른 해제, Bearer 검색·MCP 도구 목록 및 쿠키만으로 MCP 거부를 검사했다. TOTP 단위 테스트는 RFC SHA-1 벡터 6개와 창 경계/재생을 검사한다. 보안 단위 테스트는 대기 세션 만료와 성공 핸들러 전 ADMIN 세션 저장 차단을 검사한다.

런타임 최대 애플리케이션 RSS는 289760 KiB(283.0 MiB), 시작 중 최대는 173932 KiB였다. Gradle/Node와 파일 캐시는 이 수치에 포함하지 않는다. 실제 cgroup 512 MiB 보장은 아니다.

## 아티팩트

- `app/build/reports/tests/test/index.html`, `core/build/reports/tests/test/index.html`: 테스트 상세.
- `build/verification/totp-build.log`: 최종 빌드 출력.
- `build/verification/totp-initial-test.log`: 최초 실패 근거(기대 예외 수정 전).
- `build/verification/totp-runtime.log`, `smoke-off.txt`, `measurement-off.json`, `bootrun-off.log`: curl/RSS/서버 종료 기록.
- `build/verification/totp-recovery.txt`: 실행 JAR 오프라인 복구 결과.

위 build 아티팩트는 gitignore 대상이다. 비밀번호·등록 비밀·복구 코드는 출력하지 않는다.

## 미수행·남은 한계

- 실제 브라우저의 한/영·모바일·테마·접근성/입력 동작 검증은 브라우저 환경이 없어 미수행이다. TypeScript/Vite 빌드와 HTTP 정적 리소스 제공만 검증했다. ROADMAP의 UI 실검증 항목은 미완료로 유지했다.
- Docker 실제 512 MiB cgroup·arm64·실제 NAS·대규모/동시 부하 검증은 이번 범위에 포함하지 않았다. 기존 내장 임베딩 512 MiB 초과 제한도 유지한다.
- TOTP는 서버·기기 시각 동기화가 필요하다. 단일 관리자·단일 프로세스 H2 구조이며 로그인 제한의 분산 공격/상태 축출 한계는 기존과 같다.
- 데이터 폴더와 암호화 키의 접근 권한이 복구 권한이다. 오프라인 명령은 서버 종료를 운영자가 수행한 뒤 사용한다. 기존 API 토큰은 자동 폐기하지 않는다.

## 교차 검토 반영 (2026-10-24, 기준 커밋 `ad6a3b1`)

| # | 지적 | 조치 | 테스트 |
| --- | --- | --- | --- |
| 1 | 로그인 시도 제한 확인→검증→기록이 원자적이지 않음, 잠금 후 실패가 횟수를 1로 되돌려 15분 잠금을 1초로 덮어씀 | `LoginAttempts.acquire`가 허용 확인과 진행 중 시도 예약을 한 잠금 안에서 처리(`count + inFlight < 5`, 잠금 뒤에는 키별 1개). `failed`는 횟수를 되돌리지 않고 `retryAt = max(기존, 새 값)`. 비밀번호 로그인(`LoginGuard`→성공/실패 핸들러가 같은 표 사용)과 TOTP 로그인·등록·해제 모두 적용. 공유 비밀번호 잠금도 횟수 리셋을 없애고 허용 시각을 줄이지 않게 수정(동시 요청은 기존 잠금 안 재확인으로 결과 폐기). 대기 세션별 실패 5회에서 `totp.pending` 삭제 | `TotpTest.concurrentFailuresStayWithinLimitAndLockIsNotShortened`(32스레드 동시 실패 → 허용 ≤5, 진행 중 예약, 잠금 뒤 재잠금 15분), `TotpIntegrationTest`(16스레드 동시 틀린 코드 → 실제 검증 감사 1~5회, 세션별 5회 실패 후 대기 삭제), `SecurityBoundaryTest` 갱신, `ApplicationIntegrationTest` 공유 잠금 만료 후 재실패 15분 |
| 2 | 등록에 비밀번호 재확인 없음 | `/api/totp/enroll`에 현재 비밀번호 필요(해제와 같은 수준). 보안 화면 등록 버튼을 비밀번호 폼으로 변경 | 본문 없음·틀린 비밀번호 401, 올바른 비밀번호 200 |
| 3 | 등록·해제 실패가 로그인 키를 잠금 | 등록·확인·해제는 `totp-manage:` 접두어 키로 별도 제한 | 해제 실패로 관리 키 15분 잠금(429) 상태에서도 비밀번호 로그인 202 |
| 4 | 키 누락/변경 시 조용히 새 키 생성, 복호화 실패 500, AAD 없음 | 키 파일이 없고 DB TOTP 비밀 또는 임베딩 암호문이 있으면 새 키를 만들지 않고 `KeyUnavailable`(복원 또는 `--recover-admin` 안내). 시작 시 TOTP/임베딩 복호화 실패를 ERROR 로그로 알리고 서버는 계속(복구 코드 로그인 가능). 사용 시 503 통제 오류. 암호문은 `v2:` 형식 + 용도별 AAD(`totp`,`embedding`), 접두어 없는 기존 형식은 AAD 없이 읽고 TOTP는 다음 승인 단계 저장 때, 임베딩은 다음 설정 저장 때 새 형식으로 저장 | `TotpTest.purposeBoundCiphertextLegacyFormatAndMissingKey`(용도 교차 거부, 기존 형식 읽기, 키 변경·누락 오류, 키 미생성), 통합 테스트에서 DB 비밀 `v2:` 확인 |
| 5 | 복구 코드 입력 정규화 | trim·소문자화·공백/하이픈 제거 후 40자리 16진수만 복구 코드로 처리 | `recoveryCodeNormalization`, 통합/스모크에서 대문자·하이픈·공백 포함 복구 코드 로그인 |
| 6 | 오프라인 복구 입력 | 터미널 콘솔은 확인 재입력·불일치 실패. 파이프 입력 줄 끝 CR 제거 | `pipedRecoveryPasswordStripsCarriageReturn`, 실행 JAR 복구 스크립트 |
| 7 | 코드 단계 취소 없음 | `DELETE /api/login/totp`로 대기 세션 정리(실패 기록 유지·감사), 로그인 화면 한/영 취소 버튼 | 취소 후 `totpRequired=false`, 코드 제출 401, 재로그인 202. 스모크에서도 확인 |

판단한 것: 키 오류 시 서버 시작을 막지 않았다(검색·MCP 토큰은 계속 동작, 관리자는 복구 코드 또는 오프라인 복구로 해결). 잠금(5회) 이후에는 키별 한 번에 한 시도만 허용하고 실패 시 다시 15분 잠근다. 실패 기록은 마지막 허용 시각 15분 뒤 진행 중 시도가 없을 때 정리된다(기존 정리 정책 유지).

| 명령 | 종료 코드 | 결과 |
| --- | --- | --- |
| `./gradlew :app:test -q` (최초) | 1 | 14개 중 1개 실패: 등록 비밀번호 401 직후 1초 지연으로 429 — 의도한 동작이라 테스트에 초기화 추가 |
| `./gradlew :app:test -q` | 0 | app 14개 통과 |
| `./gradlew build -q` (1회차) | 1 | `ApplicationIntegrationTest.verifyRemoteEmbedding` 키워드 검색 결과 0건(색인 타이밍). 변경 코드와 무관하며 직전 app 테스트와 재실행에서 통과. 근거 `build/verification/totp-review-build-run1-failed.log` |
| `./gradlew build -q` (최종) | 0 | core·app 테스트, TypeScript·Vite·패키징 통과 |
| `VERIFY_PORT=8121 python3 scripts/verify-runtime.py` | 0 | 비밀번호 확인 등록·대기 거부/취소·코드/정규화 복구 로그인·해제 포함 전체 스모크. 최대 RSS 289436 KiB(282.7 MiB), 서버 종료·포트 반환 확인 |
| `python3 scripts/verify-admin-recovery.py` | 0 | 실행 JAR 복구(파이프 입력) |

미수행: 실제 브라우저에서 등록 비밀번호 폼·취소 버튼 시각/동작 검증(환경 없음), 터미널 콘솔 확인 재입력의 실제 TTY 검증(자동 테스트는 파이프 입력만), 키 오류 503 경로의 HTTP 통합 테스트(단위 테스트로 예외만 검증). 원격 임베딩 색인 타이밍 테스트의 간헐 실패 가능성은 별도 확인 필요.

아티팩트: `build/verification/totp-review-apptest.log`, `totp-review-build.log`, `totp-review-build-run1-failed.log`, `totp-review-runtime.log`, `totp-review-recovery.txt`.

## 변경 파일

- 서버: `SecurityConfiguration.java`, `ApiController.java`, `Storage.java`, `EmbeddingSettings.java`, `NasquireApplication.java`, 신규 `Totp.java`, `TotpService.java`, `TotpController.java`, `AdminRecovery.java` (모두 `app/src/main/java/com/bluesounds/nasquire/app/`).
- 테스트: `app/src/test/java/com/bluesounds/nasquire/app/SecurityBoundaryTest.java`, 신규 `TotpIntegrationTest.java`, `TotpTest.java` (같은 디렉터리).
- 화면: `frontend/src/App.tsx`, 신규 `frontend/src/Security.tsx`.
- 검증: `scripts/smoke.py`, 신규 `scripts/verify-admin-recovery.py`.
- 문서: `README.md`, `CLAUDE.md`, `docs/ROADMAP.md`, 신규 `docs/verification/totp.md`.
- 커밋 위임 메시지: `.ai-commit-msg` (gitignore 대상). 직접 git add/commit/push하지 않았다.
