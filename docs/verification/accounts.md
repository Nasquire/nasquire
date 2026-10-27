# 다중 계정·역할·소스 권한 검증

기준: `7cedb3c`. 기존 의존성만 사용했다. 일반 원본 소스·라이선스 파일은 변경하지 않았다.

## 구현과 판단한 것

- 기존 `administrator` ID 1의 비밀번호 해시·TOTP 암호문·마지막 승인 단계·복구 코드·generation을 보존한다. 역할 ADMIN·활성 상태와 토큰/OAuth 승인 소유자 1을 추가한다. 재시작 시 이관은 반복 가능하다.
- 사용자 생성·역할/활성/소스 변경·비밀번호/TOTP 초기화·삭제는 ADMIN 세션과 헤더 CSRF가 필요하다. 사용자명은 ASCII 3~100자, 초기/재설정 비밀번호는 최초 설정과 같은 정책이다.
- 마지막 활성 관리자 보호는 `account_lock` 행의 `SELECT ... FOR UPDATE`와 같은 트랜잭션 안의 검사·변경으로 처리한다. 삭제는 사용자명 익명화·비밀번호/TOTP/복구 코드/소스 권한 폐기·토큰 철회 후 묘비 행을 보존한다. 목록/로그인/복구에서 제외하고 ID 재사용과 기존 자격 증명의 재활성화를 막는다. 감사 행은 유지한다.
- 역할·권한·상태·비밀번호/TOTP 초기화는 사용자 세대를 올린다. 로그인·TOTP 대기 세션은 사용자 ID/세대를 비교해 다음 요청에서 폐기한다. 자기 계정 변경은 기존 CAS·세션 ID/CSRF 교체를 유지한다.
- ADMIN은 전체 소스, USER의 초기 허용 집합은 빈 집합이다. 세션 읽기와 다운로드 발급/수신, API 토큰, MCP/OAuth는 소유자의 현재 권한을 다시 계산한다. API 토큰의 빈 범위는 기존 호환성을 위해 소유자에게 허용된 전체 소스라는 뜻을 유지한다. OAuth 빈 승인 집합은 권한 없음이다.
- API 토큰은 소유자만 조회/철회/삭제하며 ADMIN은 전체를 관리한다. OAuth 승인 HTML에는 로그인 사용자의 허용 소스만 표시하고 서버에서도 검사한다. 비활성/삭제된 소유자의 OAuth 코드 교환·갱신도 거부한다.
- 감사는 기존 actor와 함께 actor_id/actor_name을 기록한다. 시스템·익명 실패에는 사용자 식별값을 만들지 않는다. 비밀번호·토큰 원문은 기록하지 않는다.
- 작은 DB 연결 풀에서 교착하지 않도록 토큰 행과 사용자 목록 결과 집합을 먼저 반환한 뒤 소유자/소스 권한을 조회한다.
- 오프라인 복구는 `--username=`으로 ADMIN을 선택한다. 하나일 때만 생략하며 여럿이면 목록과 함께 거부한다. 비활성 ADMIN 활성화·비밀번호/TOTP/복구 코드·세대·감사를 트랜잭션 처리한다. 비밀번호는 표준 입력으로만 받는다.

강등·삭제된 관리자가 만든 공유는 유지되며 관리자 공유 화면에서 철회한다. 세션 다운로드 티켓은 세션 무효화 후에도 TTL 동안 유효하지만, 수신 시 계정 활성 상태와 현재 소스 권한을 재검사한다.

## 자동 검증 범위

`AccountsMigrationTest`:

- 기존 단일 관리자 스키마의 CHECK 제약 확장, 비밀번호/TOTP/단계/복구 코드/generation 보존.
- 기존 API 토큰과 OAuth 승인 소유자 1, 기존 코드 해시 보존·반복 이관.
- 복구의 다중 관리자 모호성·USER 지정 거부·비활성 ADMIN 지정 복구·다른 사용자 비밀번호/복구 코드 보존·감사 actor.

`AccountsIntegrationTest`(실제 HTTP, 8104):

- USER 관리 API 403, 소스 밖 검색/문서/다운로드/MCP 도구 거부, 허용 소스 검색 성공, 기본 0개 결과.
- 소유 토큰만 조회·다른 사용자 토큰 철회 거부·ADMIN 전체 조회/소유자 표시·범위 밖 토큰 생성 거부.
- 동시 Bearer 검색·API 토큰/OAuth 교집합·소스 권한 축소 및 비활성 즉시 반영·세션/토큰 다운로드 티켓 수신 재검사.
- USER OAuth 승인 화면 소스 제한·소유자 결합, 사용자 TOTP/복구 코드 분리·관리자 초기화 시 대기 세션 무효화.
- 비밀번호 재설정/삭제/역할 변경 세션 무효화, 마지막 활성 ADMIN의 삭제/강등/비활성 거부·동시 변경 한 요청만 성공.

기존 계정 변경·TOTP·CSRF·OAuth/공유/다운로드 회귀 테스트도 함께 실행한다. 브라우저 스크립트는 기존 화면에 ADMIN 사용자 관리와 USER 한/영 × 데스크톱/모바일 검색·내 토큰·환경설정·관리자 설정 숨김·axe 검증을 추가한다.

## 실행 결과

| 명령 | 종료 코드 | 결과 |
|---|---:|---|
| `./gradlew :app:test -q` (수정 후) | 0 | 기존 회귀와 신규 이관/권한/복구 테스트 통과 |
| `./gradlew build -q` (최종) | 0 | core 34·app 68, 실패/오류 0; lite에서 full 전용 app 테스트 2건 조건부 생략 |
| `./gradlew -Pfull build -q` | 0 | core 34·app 68·full 21, 실패/오류/생략 0 |
| `VERIFY_PORT=8104 python3 scripts/verify-runtime.py` | 0 | 48문서 curl 스모크·업로드/공유·서버 종료/포트 반환 통과 |
| `PLAYWRIGHT_BROWSERS_PATH=build/verification/deps/browsers build/verification/venv/bin/python scripts/verify-browser.py` (lite, 최종) | 0 | 120개 화면, axe 위반/콘솔·요청 실패/HTTP 오류 0, USER 포함·서버 종료/포트 반환 |
| `python3 -m py_compile scripts/verify-browser.py` | 0 | 문법 검사 통과 |
| `git diff --check` | 0 | 공백 오류 없음 |
| 내부 식별자·도메인·경로 `rg` (추적·신규 공개 파일과 커밋 메시지) | 1 | 일치 0건, 정상 무일치 |

lite/full 빌드와 런타임·브라우저는 순차 실행했다. 런타임/새 HTTP 통합 테스트는 8104만 사용했다. 기존 테스트의 임의 로컬 포트 구성은 유지했다. 브라우저 최종 재실행에는 USER의 콘솔·네트워크 오류 수집도 포함한다. 도구 설치나 의존성 추가는 하지 않았다.

각 명령의 로그는 `build/verification/accounts/`에 있다. 기존 검증 자료는 같은 폴더의 `browser-before-*`로 보존했고, 첫 브라우저 통과 자료는 `browser-first/`, 최종 자료는 `build/verification/browser/`에 있다.

## 실패와 수정 기록

1. 첫 `:app:test` 종료 1: 계정 변경 제한의 키가 고정 `administrator`에서 사용자명으로 바뀌었으나 기존 테스트는 옛 키만 초기화했다. 사용자별 키로 테스트를 수정했다.
2. 두 번째 `:app:test` 종료 1: 신규 MCP 경계 테스트가 존재하지 않는 도구 `search`를 호출했다. 실제 도구 `search_documents`로 바꾸고 `result.isError=true`를 검증하도록 수정했다.
3. 세 번째 `:app:test` 종료 0. 이후 동시 Bearer·역할 변경·토큰 다운로드 수신 회귀를 추가하고 전체 빌드에서 다시 검증한다.

## 검증 한계

로컬 H2·HTTP/Chromium 범위다. HTTPS 프록시·외부 MCP 클라이언트·NAS 실기/ACL·실제 모바일 기기·대규모 계정 부하는 이번 검증에 포함하지 않는다. 이 기준에서는 일반 OIDC 로그인과 경로/그룹 단위 권한이 미구현이었다. OIDC 후속 구현은 [별도 검증](oidc.md)을 참고하며 소스 단위 권한은 유지한다. 과거 감사 행에는 새 actor_id/actor_name이 없어 NULL로 유지한다.

상세 로컬 아티팩트: `build/verification/accounts/`, `app/build/test-results/test/`, `build/verification/browser/`, `build/verification/measurement.json`, `build/verification/smoke.txt`.

## 인증 교차 검토 반영 (기준 4b274c5)

현재 계정 판정 실패는 403 예외로 거부한다. 최초 설정 감사는 명시적 사용자 ID를 유지하며, 익명·미확인 감사에 ID 1을 추정하지 않는다. TOTP 대기 검증은 세션의 ID·활성·세대를 확인한 뒤 명시적 ID로만 수행한다. 기존 인증 없는 직접 호출에 기대던 테스트 픽스처에는 인증 범위를 명시했다.

이관은 기존 계정 역할·자격 증명을 보존하고 이후 role 기본값을 USER로 바꾸며 최초 설정 INSERT는 ADMIN을 명시한다. 기존 단일 계정의 id=1 CHECK만 제거하고 다른 CHECK는 재시작 후에도 보존한다. 계정 삭제는 외부 연결·최초 허용 email/sub·연결 기준·issuer도 같은 트랜잭션에서 지우며 ID 묘비는 유지한다. USER의 OAuth/OIDC 설정·철회·연결 시험 쓰기 요청 403을 검사한다.

초기 `:app:test` 두 차례는 각각 종료 1(7건/3건 실패), 첫 lite 빌드도 종료 1(1건 실패)이었다. 공개 `/api/auth`에서 인증 전에 admin()을 조회하던 경로를 인증 여부로 분기했다. 나머지는 기존 테스트가 HTTP 요청 밖에서 인증 없이 토큰·검색·OAuth 승인을 직접 호출하던 부분으로, 명시적 테스트 인증 문맥을 제공하여 수정했다. 보안 거부 동작은 유지했다. 초기 로그는 `build/verification/auth-review/test-first.log`, `test-second.log`, `build-lite-first.log`에 보존했다.

최종 필수 검증은 순차 실행했다. lite는 core 34·app 69건, 실패/오류 0·full 전용 2건 생략이며 full은 core 34·app 69·full 21건 모두 실패/오류/생략 0이다. 런타임은 8104에서 48문서·16 MiB 업로드·공유·TOTP·curl 스모크를 통과하고 서버/포트 정리를 확인했다. RSS 측정은 단독 컨테이너 제한 보장이 아니다.

| 명령 | 종료 코드 | 결과 |
|---|---:|---|
| `./gradlew build -q` | 0 | lite 빌드·회귀 통과 |
| `./gradlew -Pfull build -q` | 0 | full 빌드·전체 124건 통과 |
| `VERIFY_PORT=8104 python3 scripts/verify-runtime.py` | 0 | curl 스모크·서버 종료/포트 반환 |
| `PLAYWRIGHT_BROWSERS_PATH=build/verification/deps/browsers build/verification/venv/bin/python scripts/verify-browser.py` | 0 | lite 121개 화면·19개 검사, axe/콘솔/실패 요청/HTTP 오류 0·서버 종료 |
| 최종 summary·테스트 XML·8104 소켓 확인 | 0 | 전체 124건 실패/오류/생략 0, 포트 반환 |
| 내부 식별자·도메인·경로 `rg` | 1 | 추적·신규 공개 파일과 커밋 메시지 무일치 |
| `git diff --check` | 0 | 공백 오류 없음 |

감사도 사용자명 문자열 대신 익명 인증 토큰 종류를 검사한다. 실제 계정명과 같은 익명 principal이 있어도 현재 계정 판정은 거부하고 감사 actor_id는 NULL임을 회귀로 확인했다. 이 보완 뒤 필수 검증을 재실행했다.

사설 IP 대역 전체까지 넓힌 추가 검사에는 기존 보안 테스트의 일반 사설 주소 예시 1건이 탐지됐다. 실제 내부 정보가 아닌 평문 HTTP 정책 테스트이므로 유지했다. 해당 추가 검사 래퍼의 종료 1과 판정 근거는 `build/verification/auth-review/failure-history.txt`, `private-ip-scan.log`에 기록했다. 내부 식별자·도메인·사내 경로 검사는 무일치다.

필수 미실행 검증은 없다. 실제 외부 IdP는 작업 지시로 접속하지 않았으며 HTTPS 프록시·NAS 실기·대규모 부하는 실환경이 없어 미검증이다. 최종 명령별 로그/종료 코드·실패 이력·테스트 XML·브라우저 요약은 `build/verification/auth-review/`에 보존했다. 최종 요약 검사 래퍼의 배열/정수 비교 오류(종료 1)도 수정 후 종료 0이며 상세는 같은 폴더의 `failure-history.txt`에 기록했다.
