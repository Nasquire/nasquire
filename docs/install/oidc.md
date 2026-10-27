# 선택 OIDC 외부 로그인 / Optional OIDC sign in

## 한국어

OIDC는 기본 꺼짐이다. MCP OAuth 인가 서버와 별개의 브라우저 로그인 기능이며 외부 토큰을 MCP에 전달하거나 MCP 자격 증명으로 사용하지 않는다. 로컬 사용자명/비밀번호 로그인과 오프라인 관리자 복구는 항상 유지한다.

1. `nasquire.public-url=https://search.example.com`을 설정하고 HTTPS 리버스 프록시를 준비한다. 공개 기본 주소는 경로 없는 origin이어야 한다. 요청의 Host·전달 헤더로 callback 주소를 만들지 않는다. 개발 환경에서만 기존 OAuth와 동일하게 `localhost`, `127.0.0.1`, `[::1]`의 HTTP를 허용한다. issuer와 discovery가 제공하는 모든 endpoint에도 HTTPS 규칙을 적용한다.
2. IdP(예: issuer `https://idp.example.com/realms/example`)에 confidential web client를 만든다. authorization code와 PKCE S256, RS256 또는 ES256을 활성화한다. IdP가 discovery에서 `code_challenge_methods_supported: ["S256"]`을 알려야 한다. client 인증은 `client_secret_basic`을 우선하며 discovery가 post만 지원하면 `client_secret_post`를 사용한다. 콜백을 **`https://search.example.com/oidc/callback`** 하나로 정확히 등록한다. wildcard는 쓰지 않는다.
3. 관리자 환경설정 → **외부 로그인**에 issuer·client ID·비밀을 입력한다. 기본 scopes는 `openid email profile`, 자동 가입은 꺼짐이다. 버튼 이름을 지정하고 discovery 연결 시험을 수행한다. 연결 시험은 저장된 issuer의 discovery만 확인하며 토큰 교환이나 사용자 인증은 하지 않는다. 활성화 전에 클라이언트와 HTTPS 조건을 만족해야 한다.
4. 사용자 관리에서 기존 계정의 **최초 외부 email 또는 sub** 값을 저장한다. 저장 시점의 issuer와 연결 기준도 함께 고정된다. `email`은 대소문자를 포함한 정확한 값이며 최초 연결에 `email_verified=true`가 필요하다. `sub`는 해당 issuer의 정확한 subject 값이다. 연결 후에는 고정된 `(issuer, sub)`만 사용하므로 이메일이 달라져도 다른 subject가 계정을 가져갈 수 없다. issuer나 기준을 바꿨다면 필요한 계정의 허용값도 다시 저장한다.
5. 자동 가입을 켠 경우 미연결 사용자는 임의 사용자명의 **USER·소스 권한 0개** 계정으로 생성한다. email 기준이면 최초 가입에도 검증된 email이 필요하다. 자동 생성 계정의 로컬 비밀번호는 없으며 관리자가 비밀번호를 설정하면 로컬 로그인도 가능하다. 그룹 매핑이 있어도 생성 첫 로그인은 USER로 유지한다.
6. 그룹 클레임 이름과 관리자 그룹을 모두 지정하면 다음 로그인부터 문자열 배열 클레임의 정확한 그룹 포함 여부로 ADMIN/USER를 동기화한다. 누락되거나 배열이 아니면 ADMIN 그룹에 속하지 않은 것으로 처리한다. 마지막 활성 ADMIN의 강등은 로그인 자체를 거부하고 DB 변경을 롤백한다. 비활성/삭제 계정은 거부한다.

관리자 계정은 email보다 **sub 연결을 권장**한다. 사용자가 이메일을 바꿀 수 있는 IdP에서는 `email_verified=true`여도 다른 계정의 허용 이메일을 선택하여 최초 연결을 가로챌 위험이 있다. IdP에서 이메일 변경·재할당 정책을 확인한다.

**로컬 TOTP 정책:** 안전한 기본값으로 IdP 인증 뒤에도 해당 계정의 로컬 TOTP 또는 미사용 복구 코드를 반드시 요구한다. 우회 옵션은 제공하지 않는다. TOTP 완료 전 세션에는 검색·관리 권한이 없고 그룹 역할·generation도 바꾸지 않는다. 성공 시 세션 ID·CSRF·계정 generation 검사를 기존 로그인과 동일하게 적용한다.

외부 연결 해제는 연결과 최초 허용값을 함께 지우고 generation을 올려 기존 세션을 폐기한다. 자동 가입이 꺼져 있으면 다음 외부 로그인은 거부된다. 자동 가입이 켜져 있으면 새 USER 계정이 만들어질 수 있으므로 필요한 경우 먼저 자동 가입을 끈다. 삭제 시 외부 연결과 최초 허용값은 제거하므로 같은 외부 계정을 새 계정에 연결할 수 있다. 로컬 계정 ID 묘비는 유지한다.

`oidc.properties`는 데이터 폴더에 0600으로 저장한다. client secret은 기존 `embedding.key`를 사용한 AES-GCM v2, AAD `oidc`로만 저장하고 API에는 설정 여부만 반환한다. issuer 변경은 이전 비밀을 폐기하며 새 비밀을 함께 주지 않으면 OIDC를 끈다. 비밀 삭제 API(`DELETE /api/oidc/secret`)도 OIDC를 끈다. 암호화 키 누락/불일치는 OIDC만 비활성화하고 로컬 로그인을 유지한다. 기존 암호문이 있으면 다른 기능에서도 공유 키를 새로 생성하지 않는다. 데이터 설정과 `embedding.key`를 함께 백업·복원한다.

관리 API: `GET/PUT /api/oidc`, `DELETE /api/oidc/secret`, `POST /api/oidc/test`; ADMIN 세션과 변경 시 헤더 CSRF가 필요하다. 연결 허용값은 `PUT /api/users/{id}/oidc`의 `{"match":"reader@example.com"}`, 해제는 `DELETE /api/users/{id}/oidc`다. 설정 JSON 값은 문자열이며 enabled/autoSignup은 `"true"`/`"false"`다.

프록시는 HTTPS 종료·원래 공개 URL 라우팅을 정확히 구성하고 `/oidc/callback`의 쿼리(코드/state)와 Authorization/토큰 본문을 access/error 로그에 남기지 않는다. `nasquire.trusted-proxies`와 forwarded-header 전략은 신뢰하는 프록시에만 허용한다. state/nonce/PKCE는 세션당 하나·5분·1회용이며 새 로그인은 이전 시도를 교체한다. 설정 변경은 진행 중 로그인을 폐기한다. 실패는 사용자 존재 여부 없는 일반 화면이며 IP 기준 시도 제한을 적용한다.

원격 호출은 리디렉트를 따르지 않고 호출당 5초·응답 256 KiB 상한이다. discovery/JWKS 캐시는 최대 5분이고 알 수 없는 kid는 1회 재조회한다(재조회 간 최소 1초). ID 토큰은 RS256(2048비트 이상)/ES256(P-256)만 허용하며 iss/aud/azp/exp/iat/nbf/nonce를 검사한다. 시계 오차는 30초, iat는 최근 10분 이내다. 로그아웃은 Nasquire 세션만 종료하며 IdP single logout은 지원하지 않는다.

## English

OIDC browser sign in is **off by default** and independent of the MCP OAuth authorization server. External IdP tokens never become MCP credentials. Local password sign in and offline administrator recovery remain available.

Set `nasquire.public-url=https://search.example.com` to an HTTPS origin without a path. Configure a confidential client at an issuer such as `https://idp.example.com/realms/example` with authorization code, PKCE S256, RS256 or ES256, and the exact callback **`https://search.example.com/oidc/callback`**. Discovery must advertise code and S256. Client authentication prefers `client_secret_basic`, with advertised `client_secret_post` as a fallback. All endpoints must use HTTPS; only literal loopback hosts (`localhost`, `127.0.0.1`, `[::1]`) may use HTTP for development.

In administrator Settings → **External sign in**, save the issuer, client ID, secret, scopes (default `openid email profile`) and button label. Test discovery before enabling. This test fetches discovery only; it does not authenticate users or exchange tokens. Callback URLs always derive from the configured public URL, never incoming Host or forwarded headers.

Pre-create local accounts and save each account's initial email or subject in Users. The configured issuer and matching mode are pinned when saved. Email matching is exact and case sensitive and requires `email_verified=true` for initial linking. Subject matching requires the exact `sub`. Subsequent logins use the pinned `(issuer, sub)` identity; a different subject cannot take over an already linked account. Changing the configured issuer or mode requires saving the intended initial match again.

Automatic sign up defaults to off. When enabled, unknown identities get a random local username, USER role and **zero allowed sources**. Email mode requires verified email for registration too. These accounts have no local password until an administrator sets one. The first registration remains USER even with group mapping configured. On subsequent logins, an optional string-array group claim and exact administrator group synchronize ADMIN/USER. Missing or malformed groups imply USER. Demoting the last active administrator rejects the login and rolls back changes. Disabled/deleted accounts cannot sign in.

Prefer **subject (sub) linking for administrator accounts**. If an IdP lets users change their email, even `email_verified=true` can allow a user to select another account’s allowed email and take over its initial link. Review the IdP’s email change and reassignment policies.

**Local TOTP remains mandatory** for any account that has enabled it, after IdP authentication. There is no bypass option. Group role synchronization and generation changes wait until TOTP approval. The existing unauthenticated TOTP challenge also accepts an unused recovery code. Authentication rotates the session and CSRF and retains account generation checks.

Unlinking removes both the external link and initial match and invalidates account sessions. With automatic sign up enabled, a later external login may create a fresh USER account; disable automatic sign up first when that is unwanted. Deletion removes external links and initial matches, allowing the same external identity to link to a new account. The local account ID tombstone remains.

Settings are stored in the data folder's 0600 `oidc.properties`. The client secret uses the existing `embedding.key`, AES-GCM v2 and AAD `oidc`. APIs return only whether a secret exists. Changing issuer discards the old secret and disables OIDC unless a new secret is supplied. Deleting the secret also disables OIDC. Missing/mismatched keys disable OIDC while preserving local sign in; existing ciphertext prevents silent shared-key regeneration. Back up and restore settings together with `embedding.key`.

Administrator APIs are `GET/PUT /api/oidc`, `DELETE /api/oidc/secret`, `POST /api/oidc/test`; mutations require header CSRF. User initial matches use `PUT /api/users/{id}/oidc` with `{"match":"reader@example.com"}` and unlink with DELETE. Setting values are strings; enabled/autoSignup accept `"true"` or `"false"`.

Configure TLS termination and routing carefully. Suppress callback query strings, authorization headers and token bodies from proxy access/error logs. Trust forwarded headers only from explicitly configured proxies. State, nonce and PKCE are single use, session bound and expire after five minutes; new attempts replace previous ones and configuration changes invalidate pending attempts. Failures use a generic page and IP-based throttling.

Remote calls never follow redirects and have a five-second/256-KiB response limit. Discovery and JWKS cache for at most five minutes. An unknown kid triggers one refresh (at least one second between refreshes). ID tokens allow RS256 with RSA ≥2048 bits or ES256/P-256 only and validate iss/aud/azp/exp/iat/nbf/nonce. Clock tolerance is 30 seconds and iat must be within the last ten minutes. Sign out ends only the Nasquire session; IdP single logout is not implemented.

로컬 검증과 한계 / Local verification and limits: [OIDC 검증](../verification/oidc.md).
