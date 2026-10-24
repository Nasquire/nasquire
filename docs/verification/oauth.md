# MCP OAuth 검증 (5b, 기준 b724657)

## 구현과 선택

내장 인가 서버와 `/mcp` 보호 리소스를 기존 JDBC·관리자 세션·TOTP·CSRF·공식 MCP SDK에 연결했다. 공개 클라이언트·하나의 고정 MCP audience·읽기 전용 소스 승인만 필요하므로 Spring Authorization Server를 추가하지 않고 JDK/Spring의 기존 의존성을 사용했다. 새 라이브러리·라이선스 변경은 없다. 자체 구현 인가 서버인 만큼 향후 사양 변경과 외부 클라이언트 상호운용성 검토가 필요하다.

[2025-06-18 MCP Authorization](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization), [RFC 9728](https://www.rfc-editor.org/rfc/rfc9728), [RFC 8414](https://www.rfc-editor.org/rfc/rfc8414), [RFC 7591](https://www.rfc-editor.org/rfc/rfc7591), [RFC 8707](https://www.rfc-editor.org/rfc/rfc8707), [RFC 9207](https://www.rfc-editor.org/rfc/rfc9207), [RFC 7009](https://www.rfc-editor.org/rfc/rfc7009), [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700)을 기준으로 제한된 공개 클라이언트 프로파일을 구현했다. 세부 설정·수명·상한은 [설치 안내](../install/oauth.md)를 따른다.

관리자 승인 HTML은 클라이언트명·redirect 호스트·범위·소스 선택을 표시하고 기존 비밀번호/TOTP 경로로 로그인한다. 관리 React 화면은 API 토큰 페이지에서 클라이언트·승인 조회와 세션+CSRF 철회를 제공한다. 코드/리프레시 소비와 토큰 발급은 트랜잭션으로 묶고, 재사용 철회는 오류 응답 이전에 커밋한다. JDBC 저장소의 프록시를 거쳐 OAuth 신원 조회기를 연결하므로 다운로드 수신 때도 같은 현재 권한 검사를 한다.

## 자동 검증

`OAuthIntegrationTest` 6개, `OAuthDisabledTest` 1개, `OAuthMissingPublicUrlTest` 1개에서 다음을 확인한다.

| 검증 영역 | 확인한 내용 |
| --- | --- |
| 발견·활성 조건 | 두 보호 리소스 경로, 인가 서버 엔드포인트·S256·code·none 메타데이터, `/mcp` 401 challenge, 기본 off/공개 URL 미설정 시 GET·POST 404, 비활성 이유 |
| 등록 | 원격 HTTP·userinfo·fragment URI 거부, 루프백 IPv4/IPv6/localhost 허용, 이름·URI 개수·24 KiB 본문·총 1000개 상한, 미승인 24시간 만료, IP별 등록·토큰 속도 제한 |
| 관리자 승인 | 미로그인 및 TOTP 대기 세션의 승인 차단, 최종 TOTP 로그인 뒤 승인 화면, CSRF 없는 POST 거부, frame-ancestors, 거부 응답, state·iss 반환 |
| 코드 교환 | 승인 없는 임의 코드, S256 누락/plain/불일치, redirect/client/resource 불일치, 60초 만료, 코드 재사용과 동시 교환 시 계열 철회 |
| MCP 경계 | SDK 문맥의 승인 소스 검색만 반환, 범위 밖 검색/문서/다운로드 불가, 세 도구만 제공·쓰기 도구 없음, 빈/삭제 소스 403 insufficient_scope, 다른 audience 거부, 세션만 접근 불가 |
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
