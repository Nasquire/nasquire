# MCP OAuth 검증 (5b, 기준 b724657)

## 구현과 선택

내장 인가 서버와 `/mcp` 보호 리소스를 기존 JDBC·관리자 세션·TOTP·CSRF·공식 MCP SDK에 연결했다. 공개 클라이언트·하나의 고정 MCP audience·읽기 전용 소스 승인만 필요하므로 Spring Authorization Server를 추가하지 않고 JDK/Spring의 기존 의존성을 사용했다. 새 라이브러리·라이선스 변경은 없다. 자체 구현 인가 서버인 만큼 향후 사양 변경과 외부 클라이언트 상호운용성 검토가 필요하다.

[2025-06-18 MCP Authorization](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization), [RFC 9728](https://www.rfc-editor.org/rfc/rfc9728), [RFC 8414](https://www.rfc-editor.org/rfc/rfc8414), [RFC 7591](https://www.rfc-editor.org/rfc/rfc7591), [RFC 8707](https://www.rfc-editor.org/rfc/rfc8707), [RFC 9207](https://www.rfc-editor.org/rfc/rfc9207), [RFC 7009](https://www.rfc-editor.org/rfc/rfc7009), [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700)을 기준으로 제한된 공개 클라이언트 프로파일을 구현했다. 세부 설정·수명·상한은 [설치 안내](../install/oauth.md)를 따른다.

관리자 승인 HTML은 클라이언트명·redirect 호스트·범위·소스 선택을 표시하고 기존 비밀번호/TOTP 경로로 로그인한다. 관리 React 화면은 API 토큰 페이지에서 클라이언트·승인 조회와 세션+CSRF 철회를 제공한다. 코드/리프레시 소비와 토큰 발급은 트랜잭션으로 묶고, 재사용 철회는 오류 응답 이전에 커밋한다. JDBC 저장소의 프록시를 거쳐 OAuth 신원 조회기를 연결하므로 다운로드 수신 때도 같은 현재 권한 검사를 한다.

## 자동 검증

`OAuthIntegrationTest` 10개, `OAuthDisabledTest` 1개, `OAuthMissingPublicUrlTest` 1개에서 다음을 확인한다.

| 검증 영역 | 확인한 내용 |
| --- | --- |
| 발견·활성 조건 | 두 보호 리소스 경로, 인가 서버 엔드포인트·S256·code·none 메타데이터, `/mcp` 401 challenge, 기본 off/공개 URL 미설정 시 GET·POST 404, 비활성 이유 |
| 등록 | 원격 HTTP·userinfo·fragment URI 거부, 루프백 IPv4/IPv6/localhost 허용, 이름·URI 개수·24 KiB 본문·총 1000개 상한, 미승인 24시간 만료, IP별 등록·토큰 속도 제한 |
| 관리자 승인 | 미로그인 및 TOTP 대기 세션의 승인 차단, 최종 TOTP 로그인 뒤 승인 화면, CSRF 없는 POST 거부, frame-ancestors, 거부 응답, state·iss 반환 |
| 코드 교환 | 승인 없는 임의 코드, S256 누락/plain/불일치, redirect/client/resource 불일치, 60초 만료, 코드 재사용과 동시 교환 시 계열 철회 |
| MCP 경계 | SDK 문맥의 승인 소스 검색만 반환, 범위 밖 검색/문서/다운로드 불가, 세 도구만 제공·쓰기 도구 없음, 빈 승인 차단·빈/삭제 소스 토큰 403 insufficient_scope, 다른 audience 거부, 세션만 접근 불가 |
| 병행·다운로드 | 기존 API 토큰의 REST/MCP 접근, OAuth 토큰의 REST 거부, 다운로드 티켓 발급·수신·소스 삭제 뒤 수신 거부 |
| 갱신·철회 | 회전, 갱신 client/resource 결합, 재사용·동시 재사용 시 계열 철회, 1시간 액세스·30일 승인 상한, RFC 7009 철회, 관리자 클라이언트 철회·CSRF |
| 비밀·감사 | DB/감사에 코드·토큰·verifier 원문 없음, 등록·승인·발급·갱신·재사용·철회 감사, token 응답 no-store |

초기 OAuth 통합 테스트는 다운로드 수신에서 기대 200 대신 403으로 실패했다(종료 1). 원인은 Spring 저장소 프록시 객체의 필드에 콜백을 직접 연결해 실제 저장소의 조회기가 초기 상태였던 것이다. 메서드를 통한 연결로 수정하고 다운로드 직접 권한 조회·수신·철회 회귀 검증을 추가했다. 테스트 확장 중 `IndexingService` import 누락으로 컴파일 종료 1도 발생했고 수정했다. 이후 최종 결과는 아래와 같다.

| 명령 | 최종 종료 코드·결과 |
| --- | --- |
| `./gradlew :app:test --tests '*OAuth*Test' -q` | 0, OAuth 8개 실패·오류·생략 0 |
| `./gradlew build -q` | 0, 프런트엔드 TypeScript/Vite 포함 |
| `./gradlew -Pfull build -q` | 0, core 27·app 33·full 19개, 실패·오류·생략 0 |
| `VERIFY_PORT=8103 python3 scripts/verify-oauth.py` | 0, 아래 실제 흐름 통과 |
| `python3 -m py_compile scripts/verify-oauth.py` | 0 |
| `git diff --check` | 0 |

## 교차 검토 반영

- 등록 상한에서는 grant 없는 가장 오래된 미승인 클라이언트만 교체하고 `oauth-register-evict` 감사를 남긴다. 승인 클라이언트와 grant 보유 행은 보존하며 퇴출 후보가 없으면 503을 유지한다.
- 빈 소스 승인은 grant 없이 오류 화면과 새 pending·nonce를 발급하며, 서버에 소스가 없으면 승인 버튼 대신 등록 안내를 표시한다.
- `/mcp`의 401에만 기본 Bearer 메타데이터를 제공하고, 범위 부족 403에는 error·scope·메타데이터를 함께 제공한다. 200·Origin 거부 403에는 challenge가 없다.
- 회귀 테스트 포함 OAuth 12개와 `./gradlew build -q`·`./gradlew -Pfull build -q` 검증 결과는 `build/verification/oauth-review-results.json`에 기록한다.

## 실제 실행

`scripts/verify-oauth.py`가 임시 데이터의 bootRun을 띄운 뒤 curl로 메타데이터·401 challenge → DCR → 임의 비밀번호의 관리자 최초 설정/로그인 → CSRF 소스 승인 → S256 코드 교환 → `/mcp` tools/list(3개)·색인 문서 search → REST 401 → 리프레시 회전 → 철회·이후 MCP 401을 확인했다. `state`·`iss`, token no-store도 확인했다. 검증 JVM·bootRun을 종료하고 8103 포트 반환을 확인했다.

인증값은 curl 표준 입력과 메모리로 전달하며 명령행·로그·요약 파일에 출력하지 않는다. 관리자 쿠키·임시 DB는 권한 제한 임시 폴더에 두고 종료 시 삭제한다. 개발 예외인 루프백 HTTP로 검증했으며 실제 HTTPS 프록시는 사용하지 않았다.

무시되는 상세 아티팩트:

- `build/verification/oauth-runtime.txt`: 실제 단계별 PASS·종료 확인
- `build/verification/oauth-summary.json`: 인증값 없는 단계 요약
- `build/verification/oauth-bootrun.log`: bootRun 진단 로그
- `build/verification/oauth-tests.log`: OAuth 집중 검증 로그
- `build/verification/oauth-final-commands.json`, `build/verification/oauth-build-results.json`: 명령 종료 코드·테스트 집계
- `build/verification/oauth-internal-scan.txt`: 내부 식별자·경로·토큰 패턴 일치 없음(원시 rg 종료 1, 검사 래퍼 종료 0)
- `build/verification/oauth-build-lite.log`, `build/verification/oauth-build-full.log`: 최종 빌드 로그
- `app/build/reports/tests/test/index.html`: 통합 테스트 상세

## 남은 한계

- 실제 Claude·ChatGPT 연결, 실제 HTTPS 프록시·브라우저 한/영·모바일·접근성은 미실측이다. 외부 서버 접속/배포 없이 로컬 HTTP·통합 테스트로 검증했다.
- 공개 기본 주소는 origin만 지원하며 하위 경로 배포는 지원하지 않는다. 등록 redirect URI는 HTTPS/루프백 HTTP만, 클라이언트 인증은 `none`만 지원한다. 사용자별 계정·다중 인가 서버·외부 토큰 교환은 범위 밖이다.
- 속도 제한 창은 프로세스 메모리라 재시작하면 초기화된다. 미승인 클라이언트는 24시간 뒤 논리 만료하고 등록/관리 조회 때 물리 삭제한다. 만료 승인·토큰은 상한 만료 후 하루가 지나면 같은 정리 경로에서 삭제한다. 회전 토큰의 재사용 감지용 해시는 승인 수명 동안 보관한다.
- 동시 소비는 단일 앱 인스턴스의 직렬화와 DB 트랜잭션을 사용한다. 동일 데이터 디렉터리로 여러 앱 인스턴스를 실행하는 배포는 지원하지 않는다. 대규모 DCR/승인·장기 회전 DB 부하·독립 보안 감사는 미검증이다.
- 기존 NAS/NFS 파일 I/O·full 메모리·arm64/cgroup 실측 한계는 이번 변경으로 해소하지 않았다.

## 공식 SDK 상호운용

기준 `ce94b82` 후속 검증. 공식 Python 패키지 `mcp==2.3.0`의 `OAuthClientProvider`, `streamable_http_client`, `ClientSession`을 사용한다. HTTP는 이 SDK의 `httpx2` 클라이언트를 그대로 사용한다. 설치는 `build/verification/venv`에만 수행하며 저장소 런타임 의존성에 추가하지 않는다.

```sh
# 브라우저 도구 준비는 browser.md 참고
PLAYWRIGHT_BROWSERS_PATH=build/verification/deps/browsers build/verification/venv/bin/python scripts/verify-oauth-sdk.py
```

서버는 OAuth 활성화·`nasquire.public-url=http://127.0.0.1:8104`로 실행한다. 새 DB·로그·요약은 `build/verification/oauth-sdk/` 아래에 생성하며 기존 데이터를 덮어쓰지 않는다. 다른 bootRun/빌드와 동시에 실행하지 않는다. 인증값은 메모리에만 둔다.

### 발견·수정

- 최초 실행에서 MCP 401 → 보호 리소스 메타데이터 → 인가 서버 메타데이터는 통과했으나 `/oauth/register`가 `400 invalid_client_metadata`로 실패했다. SDK는 `scope`, `application_type` 등 선택 필드를 포함한다. 기존 엄격한 필드 목록을 바꿔 미지원 선택 메타데이터는 저장·외부 조회 없이 무시하며 `scope`는 `mcp:read`만 허용한다. 응답에도 실제 허용 scope를 명시한다. 근거: [RFC 7591 §2](https://www.rfc-editor.org/rfc/rfc7591.html#section-2), [공식 Python SDK](https://github.com/modelcontextprotocol/python-sdk), [MCP 2025-06-18 인가](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization), [2025-11-25 인가](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization).
- Chromium은 승인 POST의 302 이동에도 `form-action`을 적용한다. 다른 origin의 등록된 루프백 callback으로 이동할 수 있도록, 승인 화면·응답의 CSP에 **검증된 callback origin만** 추가한다. `default-src 'self'`·`frame-ancestors 'none'`·`base-uri 'none'`은 유지한다. 임의 origin·와일드카드는 허용하지 않는다.
- 회귀 테스트는 SDK 형태의 선택 필드·무시된 필드의 비저장/비반환·미승인 상태, 잘못된 scope와 비공개 클라이언트 인증 방식 거부, 승인 CSP의 callback origin을 확인한다. 기존 S256만 허용·고정 audience/resource·관리자 승인·소스 격리·읽기 전용·갱신 재사용 철회 테스트도 유지한다.

### 실측 결과·제한

최종 `scripts/verify-oauth-sdk.py`는 **종료 코드 0**이다. 실제 HTTP 단계는 요약 JSON에 경로/상태만 기록한다.

1. `/mcp` 401·Bearer challenge → PRM(`/.well-known/oauth-protected-resource/mcp`) 200 → AS 메타데이터 200 → DCR 201.
2. 별도 Chromium 컨텍스트 관리자 로그인 → 두 소스 중 하나만 선택·승인 → 실제 다른 origin 루프백 302 → code/state/iss 수신·SDK 검증 → token 200 → MCP initialize.
3. SDK `tools/list`에서 읽기 전용 3개만 확인 → `search_documents`의 승인 소스 결과 → 미승인 소스 검색/다운로드 거부 → `download_file`·티켓 HTTP 다운로드 200.
4. SDK 로컬 만료 → SDK 자동 refresh token 교환 200 → 액세스·리프레시 모두 회전 → `tools/list` 200.
5. revoke 200 → 회전 액세스의 실제 `/mcp` 401 → 기발급 다운로드 주소 403. OAuth 관리 화면에도 클라이언트·철회된 승인 표시.

승인 화면의 1280/390px·light/dark 네 조합에서 axe 위반/가로 넘침 0, 브라우저 콘솔 오류 0이다. 다크 승인 화면 링크의 대비 부족을 재현해 링크 색상을 수정하고 포커스 외곽선도 추가했다.

아티팩트: `build/verification/oauth-sdk/summary.json`, `consent-*.png`, `management.png`, `bootrun.log`, `build/verification/oauth-sdk-run.log`. 최초 성공은 `oauth-sdk-success-1/`에도 남겼으며, 이후 두 소스 격리·승인 접근성 검사를 추가한 최종 실행도 통과했다. 실패 이력 `oauth-sdk-attempt-1/`의 DCR 400, 다른 origin callback 차단 및 callback 라우트 훅의 리다이렉트 제약을 확인한 `oauth-sdk-attempt-2/`~`6/`, 다중 소스 strict locator 오류 `7/`, 다크 대비 위반 `8/`은 모두 종료 1이며 원인 수정 후 최종 통과했다. 서버/JVM·bootRun 종료 및 8104 반환을 확인했다.

서버의 1시간 수명은 낮추지 않으며 SDK의 로컬 만료 시각만 과거로 옮겨 자동 refresh를 유도한다. 서버 측 실제 만료 거부는 기존 Clock 기반 Java 회귀 테스트에서 확인한다. 벽시계로 1시간 기다리는 실측은 수행하지 않는다.

8104 이외의 포트를 열지 않기 위해 callback은 `http://localhost:8104/LICENSE.txt`의 기존 공개 경로를 사용한다. 브라우저의 실제 302 이동 요청 이벤트에서 code/state/iss를 받아 SDK에 전달하며 별도 클라이언트 리스너는 띄우지 않는다. HTTPS 프록시·외부 데스크톱 클라이언트·실제 OS 콜백 리스너는 검증 범위 밖이다. 원격 서비스에 접속하지 않는다.


최종 `./gradlew build -q`·`./gradlew -Pfull build -q`도 모두 종료 0이다. core 27·app 38·full 19개 테스트 실패/오류/생략 0이며, 실제 서버 Clock 기반 만료·갱신 재사용/철회·소스 격리 회귀 테스트를 포함한다. 상세 빌드 결과는 `build/verification/followup/build-results.json`을 따른다.

## 2차 교차 검토 반영

검증일 2026-10-25, 기준 `a44466d`. M2의 승인 POST 후 CSP는 검증된 해당 redirect origin만 허용하고, M3의 DCR 미지 메타데이터는 무시·미저장하며 scope는 `mcp:read`만 허용하는 기존 처리를 확인했다. 두 항목을 다시 구현하지 않고 기존 회귀를 유지했다.

| 항목 | 반영 정책과 회귀 근거 |
| --- | --- |
| M1 | 엔드포인트별 IP 제한. 제한 창이 차도 유효 코드+PKCE·클라이언트·redirect·resource 또는 유효 리프레시 증명을 별도 조회해 통과시킨다. IPv6 /64 집계, 4096 창 포화 시 가장 오래된 창 축출. `saturatedTokenAndMcpBucketsKeepValidCredentialsWorking`, `OAuthRateLimitTest`에서 정상 교환/갱신·새 IP 허용 검증. 프록시 목록 없는 OAuth 시작 경고를 `OAuthDisabledTest`에서 확인. 필수 설정/전달 헤더 전략은 [설치 안내](../install/oauth.md). |
| M2·M3 | 콜백 origin CSP와 SDK 선택 메타데이터 회귀를 유지. `officialSdkMetadataKeepsPublicReadOnlyRegistration`과 승인 테스트에서 허용 origin·미지 필드 미저장·scope 제한 확인. |
| L1 | 429 및 register-evict 감사는 IP(/64)·종류별 분당 첫 1회만 기록하고 반복은 억제한다. 감사 창 4096개가 차면 새 키의 감사도 억제하여 축출을 이용한 로그 홍수를 막는다. 분산 IP 공격의 모든 개별 사건/횟수를 보존하지 않는 정책이다. `OAuthRateLimitTest`와 토큰 포화/등록 정리 테스트로 확인. |
| L2 | 이름의 Unicode Cf 코드 포인트(양방향 제어·제로폭 포함)를 거부. 승인 HTML은 전체 redirect URI(스킴·호스트·명시 포트·경로·쿼리)를 escape해 표시하고 자가 등록된 미확인 클라이언트 경고를 붙인다. `unicodeNameConsentAddressAndClientRetention` 및 루프백 콜백 승인 회귀. |
| L3 | token/revoke는 빈 쿼리를 포함해 쿼리 문자열이 있으면 파라미터 파싱 전 400 `invalid_request`. 본문 form만 허용. `queryStringsClientErrorsAndSafeAuthorizationRedirects`. |
| L4 | 정상 리프레시 회전 직후 직전 토큰을 10초 미만 동안 재제출하면 같은 액세스/리프레시 응답을 돌려준다. 후속 토큰이 이미 회전했거나 10초가 지났으면 이전 토큰 재사용으로 계열 전체를 철회한다. 유예 요청은 새 토큰을 만들거나 만료 상한을 연장하지 않는다. 동시 갱신 2건 동일 응답·유예 후 재사용 철회를 Clock/동시성 회귀로 확인. |
| L5 | 미승인은 IP(/64)의 해시별 최대 20개이며 먼저 같은 IP의 가장 오래된 미승인을 교체한다. 전체 1000개 포화 시 승인 없는 최장기 행 교체 정책도 유지. 승인 클라이언트는 마지막 승인 만료 시각을 별도 보존하고 유효 승인이 없으며 그 시각에서 30일이 지나면 다음 등록/관리 조회에서 정리한다. 기존 행은 시작 시 승인 만료 최댓값(없으면 생성 시각)으로 이전한다. 59/61일 경계·동일 /64 상한·승인 행 보호 회귀. |
| L6 | client/redirect 검증 뒤 인가 오류는 검증된 콜백으로 error·선택 state·iss를 반환. state 미제공 시 성공/거부/오류에서 생략한다. token/revoke의 invalid_client는 401, 429는 OAuth JSON과 Retry-After, 토큰을 제시한 401의 Bearer challenge에는 invalid_token을 넣는다. 관련 HTTP 오류/안전 redirect 회귀 유지·추가. |
| L7 | authenticate/identity는 조회만 수행하므로 서비스 synchronized를 제거했다. 잘못된/누락 Bearer MCP 요청을 IP(/64)당 분당 60회 제한하고 정상 Bearer는 영향받지 않는다. 포화 HTTP 회귀 및 기존 소스 격리·철회 테스트. |
| L8 | Authorization과 nonce를 하나의 Pending 레코드로 만들어 단일 세션 속성에 저장. POST에서는 세션 잠금 안에서 검증·소비한다. 대기 교체 후 이전 nonce와 소비된 nonce 재사용 거부 회귀. |
| L9 | 비밀번호 로그인 최종 성공 또는 TOTP/복구 코드 최종 성공 시각을 세션에 기록. 승인 GET/POST 모두 10분 이내 인증을 요구한다. 오래된 GET은 기존 비밀번호→선택 TOTP 로그인 화면을 보여주고, POST는 401로 거부한다. 원래 인가 URL을 다시 열어 재인증해야 한다. 오래된 GET/POST·최근 인증 후 승인과 기존 TOTP 대기/완료 HTTP 회귀. |

리프레시 응답 유예는 프로세스 메모리의 최대 1000계열 캐시로만 제공하며 DB/감사에는 원문 토큰을 저장하지 않는다. 유효기간이 지난 항목은 다음 토큰 요청 때 제거한다. 재시작 또는 캐시 포화로 응답이 없으면 유예를 제공하지 않고 기존 재사용 철회 정책으로 실패한다. 유예 동안 직전 토큰을 가진 주체가 같은 응답을 얻을 수 있으므로 HTTPS와 본문/헤더 로그 제외가 필수다. 다중 인스턴스 간 유예는 지원하지 않는다.

속도 제한은 단일 프로세스 메모리이며 대규모 분산 트래픽·표 축출 공격·유효 토큰을 보유한 주체의 부하는 프록시 경계 제한도 필요하다. 유효 토큰 예외 판별은 제한 포화 상태에서도 인덱스 기반 읽기 조회가 필요하다. 승인/발급/철회 변경의 원자성 잠금은 유지했다.

### 실행 결과

최종 명령별 결과와 상세 로그는 `build/verification/oauth-review2/`에 기록한다. 초기에 변경된 생성자 인자를 누락한 테스트의 컴파일 실패(종료 1), 직접 만든 승인 플래그 행의 보존 조건 차이로 기존 회귀 1건 실패(종료 1)가 있었으며 테스트 인자와 유효한 보존 시각 조건을 수정했다. 이후 OAuth 회귀는 종료 0이었다.


| 최종 명령 | 종료 코드 | 결과 |
| --- | ---: | --- |
| `./gradlew :app:test --tests '*OAuth*' -q` | 0 | OAuth 정책·오류·동시성·제한·최근 인증 회귀 통과 |
| `./gradlew build -q` | 0 | lite 빌드·프런트엔드·런타임 클래스 경로 검사·테스트 통과 |
| `./gradlew -Pfull build -q` | 0 | full 빌드·테스트 통과 |
| `VERIFY_PORT=8104 python3 scripts/verify-oauth.py` | 0 | DCR·로그인·CSRF 승인·코드 교환·MCP 검색·refresh·revoke 통과 |
| `PLAYWRIGHT_BROWSERS_PATH=build/verification/deps/browsers build/verification/venv/bin/python scripts/verify-oauth-sdk.py` | 0 | 공식 SDK 401 발견→DCR→실제 Chromium 승인→callback→MCP·자동 refresh·철회 통과 |
| 내부 식별자·경로·키 패턴 `rg -l` 검사 | 1 | 추적 파일·새 회귀·커밋 메시지 일치 0건(검사 래퍼 종료 0) |
| `git diff --check` | 0 | 공백 오류 없음 |

최종 테스트 합계 core 27·app 44·full 19개, 실패·오류·생략 모두 0. 빌드와 실제 서버 검증은 순서대로 실행했으며 두 스크립트는 8104만 사용하고 서버 종료/포트 반환을 확인했다. 기존 가상환경·브라우저를 사용했으며 패키지를 설치하지 않았다. 마지막 JAR는 full 빌드 산출물이고 두 실제 bootRun 검증은 기본 lite 구성이다.

상세 아티팩트는 공개 커밋에서 제외하는 `build/verification/oauth-review2/`의 `commands.json`, `test-summary.json`, `regression.log`, `lite-build.log`, `full-build.log`, `verify-oauth.log`, `verify-oauth-sdk.log`, `sdk-summary.json`, `internal-scan.json`, 초기 실패 `oauth-compile.log`·`oauth-tests.log`다. 실제 SDK 서버 로그·브라우저 자료는 `build/verification/oauth-sdk/`, curl 서버 로그·요약은 `build/verification/oauth-bootrun.log`·`oauth-summary.json`이다.

이번 수정본의 외부 HTTPS 프록시·별도 OS 콜백 리스너·데스크톱 제품 연결·다중 인스턴스·장시간/분산 부하는 환경/범위상 미검증이다. 승인 화면의 10분 만료는 서버 Clock/세션 시각 회귀로 확인했고 실제 브라우저를 10분 기다리는 실측은 하지 않았다. 배포 실측은 [운영자 전달 결과](deploy.md#운영자-추가-실측-기록-2026-10-25)이며 이번 OAuth 수정본의 배포를 의미하지 않는다. 이미지 실행·헬스체크·arm64·Actions 실행은 여전히 미검증이다.
