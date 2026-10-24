# MCP OAuth 연결

OAuth는 기본 비활성화다. 기존 API Bearer 토큰은 계속 REST와 `/mcp`에서 사용할 수 있다. OAuth 액세스 토큰은 `/mcp` 전용이며 REST에서는 401로 거부한다. MCP는 브라우저 세션 쿠키만으로 접근할 수 없다.

HTTPS 리버스 프록시를 준비하고 다음 설정으로 재시작한다. 공개 주소는 경로·쿼리·사용자 정보 없는 기본 주소(origin)여야 하며, 프록시가 호스트 헤더로 바꾸지 않는 고정 값이다.

```properties
nasquire.oauth.enabled=true
nasquire.public-url=https://nasquire.example.com
server.servlet.session.cookie.secure=true
```

컨테이너 환경변수는 `NASQUIRE_OAUTH_ENABLED=true`, `NASQUIRE_PUBLIC_URL=https://nasquire.example.com`이다. 개발 루프백 HTTP에서는 secure 쿠키 설정을 끈다. 개발에만 `http://127.0.0.1:8103`, `http://[::1]:8103`, `http://localhost:8103`을 허용한다. 공개 주소가 없거나 조건에 맞지 않으면 OAuth 메타데이터·엔드포인트는 404이며 관리 화면의 **API 토큰 → MCP OAuth**에 비활성 이유를 표시한다. 설정 토글은 서버 설정으로 관리한다.

Claude·ChatGPT 등 원격 MCP 연결 기능에서 URL `https://nasquire.example.com/mcp`를 입력하고 OAuth 연결을 시작한다. 클라이언트는 보호 리소스 메타데이터와 인가 서버 메타데이터를 발견하고 공개 클라이언트로 등록한다. 등록만으로 문서 접근은 허용되지 않는다. 브라우저에서 관리자 비밀번호로 로그인하고, TOTP가 켜져 있으면 TOTP 또는 복구 코드까지 확인한다. 클라이언트 이름·이동 호스트·`mcp:read` 범위를 확인한 후 허용할 소스를 직접 선택하고 승인한다. 소스를 하나도 선택하지 않으면 어떤 소스도 허용하지 않는다. 실제 클라이언트 제품의 요금제·연결 UI·상호운용성은 별도 확인이 필요하다.

관리 화면의 **API 토큰 → MCP OAuth**에서 등록 클라이언트와 승인 목록을 조회하고 철회한다. 클라이언트 삭제는 해당 클라이언트의 모든 승인을 철회한다. 소스를 삭제/등록 해제하면 기존 OAuth 승인에서도 즉시 제외한다. 새 소스는 다시 승인해야 한다. 기존 API 토큰의 미선택 시 전체 소스 규칙은 OAuth에 적용되지 않는다.

- 보호 리소스: `/.well-known/oauth-protected-resource`, `/.well-known/oauth-protected-resource/mcp`
- 인가 서버: `/.well-known/oauth-authorization-server`
- 등록·인가·토큰·철회: `/oauth/register`, `/oauth/authorize`, `/oauth/token`, `/oauth/revoke`
- `authorization_code` + PKCE S256과 `refresh_token`만 지원한다. 토큰 엔드포인트 인증은 공개 클라이언트 `none`만 지원한다. `resource`는 인가 요청과 토큰 교환/갱신 모두 공개 주소 + `/mcp`와 정확히 같아야 한다. redirect URI도 등록 값과 정확히 같아야 한다(루프백 포트도 등록 값과 일치해야 한다).
- DCR은 HTTPS 또는 루프백 HTTP redirect URI 최대 10개(각 2048자), 클라이언트 이름 200자, JSON 본문 24 KiB, 등록 클라이언트 총 1000개다. 지원 필드 외 메타데이터는 거부한다. 미승인 클라이언트는 24시간 뒤 무효이며 다음 등록/관리 조회에서 정리한다.
- IP당 등록 분당 10회, 토큰 분당 60회, 철회 분당 60회(성공·실패 포함)다. 전달 IP는 `nasquire.trusted-proxies`로 지정한 연결 프록시에서만 신뢰한다. 제한 창은 메모리에 있으며 재시작하면 초기화된다.
- 코드는 60초·1회용, 액세스 토큰은 최대 1시간, 승인은 최대 30일이다. 리프레시 회전은 만료 상한을 연장하지 않는다. 승인당 최초 발급 포함 최대 2048쌍까지 발급하고 상한에 이르면 철회하므로 다시 승인해야 한다. 클라이언트당 유효 승인 최대 100개다.
- 코드·리프레시 재사용을 감지하면 해당 승인 계열 전체를 철회한다. RFC 7009 철회는 액세스/리프레시 어느 토큰을 제출해도 그 승인 전체를 철회한다. 토큰·코드·verifier 원문은 DB/감사 로그에 저장하지 않는다. DB와 관리자 세션도 보호해야 한다.

프록시는 `/oauth/authorize` 쿼리(챌린지·state 포함), 응답 Location의 코드, `/oauth/token`·등록·철회 본문, Authorization 헤더, `/download` 티켓을 로그에 남기지 않도록 설정한다. 토큰은 다른 서버에 전달하지 않는다. OAuth는 읽기 전용이며 기존 MCP 세 도구(`search_documents`, `get_document`, `download_file`)만 제공한다. 다운로드 주소도 사용 때 승인 만료·철회·현재 소스 범위를 다시 검사한다.

검증 범위와 한계는 [OAuth 검증](../verification/oauth.md)을 참고한다.
