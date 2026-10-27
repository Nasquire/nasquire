# OIDC 외부 로그인 검증

기준: `18a79da`의 다중 계정·역할·소스 권한 위에 추가했다. [설치·정책 (한국어/English)](../install/oidc.md). Nimbus JOSE + JWT 10.9.1(Apache-2.0)을 추가하고 lite 클래스패스 검사를 유지했다.

## 구현 범위와 판단한 것

- 기본 off. `/api/oidc` 설정은 ADMIN 세션과 변경 시 헤더 CSRF·감사를 요구한다. 별도 0600 설정 파일과 공유 `embedding.key`의 AES-GCM v2/AAD `oidc`, 비밀 비공개·삭제·issuer 변경 시 폐기·공유 키 재생성 방지를 적용했다.
- public URL은 기존 MCP OAuth와 같은 HTTPS origin/루프백 HTTP 예외다. callback은 설정으로 고정하며 discovery issuer를 정확히 비교한다. discovery와 모든 endpoint에 HTTPS 규칙을 적용한다. code/S256과 confidential client 인증만 지원한다.
- state/nonce/PKCE는 세션당 한 시도·5분·1회용이며 실패한 callback도 소모한다. 설정 revision 변경은 진행 중 시도를 거부한다. discovery/JWKS 5분 캐시, 호출당 전체 5초/256 KiB·리디렉트 금지, kid 미발견 시 한 번 재조회한다. 재조회 간격은 최소 1초다.
- RS256(2048비트 이상)/ES256(P-256)만 허용한다. iss/aud/다중 aud의 azp, exp/iat/nbf/nonce를 검증한다. 30초 시계 오차와 최근 10분 iat를 허용한다. 토큰/코드/비밀 원문을 감사에 넣지 않으며 외부 access token은 사용하지 않는다.
- 최초 연결은 관리자가 issuer·연결 기준과 함께 지정한 email/sub만 허용한다. email은 정확한 대소문자와 verified=true가 필요하다. 고정된 issuer/sub 연결은 다른 sub로 바꿀 수 없다. 해제는 연결·최초 허용값을 함께 지우고 세션 generation을 올린다. 계정 삭제 시 외부 연결과 최초 허용값을 제거하며 로컬 ID 묘비만 유지한다. 같은 외부 계정을 새 계정에 연결할 수 있다.
- 자동 가입은 USER·소스 0개·로컬 비밀번호 없는 계정이며 첫 로그인에는 그룹 승격을 하지 않는다. 이후 그룹 역할 동기화는 로컬 TOTP가 있으면 최종 승인까지 미루며 기존 account_lock 트랜잭션에서 마지막 활성 ADMIN을 보호한다. 강등 불가 시 로그인을 거부하고 역할·세대를 유지한다. TOTP 없는 콜백에서는 같은 트랜잭션의 연결 변경도 롤백한다.
- 안전한 기본값으로 로컬 TOTP를 항상 추가 요구한다. 기존 TOTP 대기/검증·복구 코드 경로를 재사용한다. 성공 시 기존 세션 전체를 폐기하고 새 ID/CSRF 및 계정 generation을 설정한다. 로컬 로그인과 오프라인 복구는 유지한다.
- 한/영 로그인 버튼·관리자 전용 외부 로그인 설정/비밀 삭제/discovery 시험/HTTPS 안내·사용자 연결 표시/해제를 추가했다.

## 가짜 IdP 통합 테스트

`OidcIntegrationTest`는 MockMvc의 실제 보안 필터와 세션, 8104 루프백 HTTP IdP, 자체 생성 RSA/EC 키와 서명을 사용한다. 외부 IdP에는 접속하지 않는다.

- 정상 RSA/EC 로그인과 PKCE verifier·고정 callback·client_secret_basic/post 확인, 세션 ID 교체·generation·MCP 세션 단독 접근 거부.
- state 불일치·실패 후 재사용·성공 후 재사용·5분 만료·설정 변경에 따른 폐기, nonce 불일치.
- 잘못된 서명, alg none/HS256, iss/aud/exp/iat/nbf 오류, 다중 aud의 azp 누락, kid 회전 재조회.
- email_verified=false·미연결 거부, 자동 가입 USER/소스 0개, 다른 sub의 계정 탈취 거부, 비활성 계정 거부.
- 그룹 승격/강등과 마지막 활성 관리자 보호, 로컬 TOTP 대기 및 실제 코드 완료, sub 기준 연결·해제 후 재연결 거부.
- 관리자/USER/비인증 접근·헤더 CSRF, 비밀 AES-GCM v2 암호화·0600·비공개·issuer 변경 폐기·삭제, 비루프백 HTTP 거부, public URL 제한, 누락/불일치 키 비활성·새 키 생성 금지.
- discovery issuer 불일치·리디렉트·응답 크기 초과·5.5초 지연 응답의 5초 시간 제한 거부, 성공 actor 및 감사에 코드/비밀 원문 없음.

브라우저 검증은 ADMIN 설정 저장·HTTPS 안내·비밀 삭제·활성 로그인 버튼의 href, 한/영·데스크톱/모바일 설정 표시, USER에게 섹션 숨김을 검사한다. 버튼 검사에만 `idp.example.com` 설정을 저장하며 클릭/discovery 요청은 하지 않는다.

## 실패 이력

1. 첫 신규 테스트 종료 1: MCP 세션 단독 접근 검사에서 CSRF 없는 POST가 먼저 403을 반환했다. 헤더 CSRF를 추가했다.
2. 두 번째 신규 테스트 종료 1: MockMvc의 servletPath 기본값이 비어 있어 MCP 전용 필터 분기를 검사하지 못했다(404). 실제 경로를 명시했다.
3. 수정 후 신규 테스트 종료 0.
4. 첫 lite 전체 빌드 종료 1: 기존 `AccountsIntegrationTest`가 캐시한 서버 컨텍스트가 8104를 점유하여 IdP 픽스처 시작이 `BindException`으로 실패했다. 해당 테스트에 `DirtiesContext`를 추가하여 클래스 종료 때 포트를 반환한다.
5. 수정 후 lite 전체 빌드 종료 0. 최종 코드의 lite/full 빌드도 각각 종료 0.
6. 첫 브라우저 실행 종료 1: 기존 검증 데이터 폴더가 있어 새 데이터 전제 검사에서 중단됐다. 기존 자료를 `build/verification/oidc/browser-before/`로 보존한 후 다시 실행하여 종료 0.

7. 최종 문서 정리 중 `git diff --check` 종료 2: README 말미에 빈 줄이 남았다. 제거 후 재검사 종료 0.

## 검증 한계

실제 외부 IdP·HTTPS 리버스 프록시·IdP별 그룹/이메일 클레임 설정·대규모 동시 로그인은 미검증이다. 실제 외부 IdP 접속은 작업 지시로 금지됐다. 실제 모바일 기기/스크린리더·IdP single logout은 검증 범위가 아니다. 그룹 매핑은 역할에만 적용하며 소스 권한은 기존 사용자별 관리 방식이다.

상세 로컬 아티팩트: `build/verification/oidc/`, `app/build/test-results/test/`, `build/verification/browser/`, `build/verification/measurement.json`, `build/verification/smoke.txt`.

## 최종 실행 결과

| 명령 | 종료 코드 | 결과 |
|---|---:|---|
| `./gradlew build -q` | 0 | lite 클래스패스 검사·core 34/app 69, 실패/오류 0; full 전용 2건 조건부 생략 |
| `./gradlew -Pfull build -q` | 0 | core 34/app 69/full 21, 실패/오류/생략 0 |
| `VERIFY_PORT=8104 python3 scripts/verify-runtime.py` | 0 | 48문서 curl·업로드/공유 회귀·서버 종료·8104 반환 |
| `PLAYWRIGHT_BROWSERS_PATH=build/verification/deps/browsers build/verification/venv/bin/python scripts/verify-browser.py` | 0 | lite 121개 화면·19개 검사, axe/콘솔/실패 요청/HTTP 오류 0, USER 한영·모바일 포함·서버 종료 |
| `python3 -m py_compile scripts/verify-browser.py` | 0 | 문법 검사 |
| `git diff --check` | 0 | 공백 오류 없음 |
| 공개 파일·신규 파일·`.ai-commit-msg` 내부 식별자/도메인/경로 `rg` | 1 | 일치 0건(정상 무일치) |
| 브라우저 summary·8104 소켓 종료 검사 | 0 | 화면 수/오류 수·서버 종료 확인 |

빌드·HTTP 픽스처·런타임·브라우저는 순차 실행했다. 신규 IdP·런타임·브라우저는 8104만 사용했으며 기존 테스트의 임의 포트는 변경하지 않았다. git add/commit/push·배포·운영 서버 접속·패키지 설치는 하지 않았다. 커밋 메시지는 무시되는 `.ai-commit-msg`에 작성했다.

## 인증 교차 검토 반영 (기준 4b274c5)

TOTP 계정의 그룹 역할·세대 변경은 최종 코드 승인 뒤에 수행한다. 대기 중 실패·만료에는 두 값이 변하지 않으며, 최종 승인 시 설정 revision·계정 활성·세대와 마지막 ADMIN을 DB 잠금 아래 재검사한다. 승인 후 세션 generation과 인증 역할도 갱신한다. TOTP 없는 계정은 콜백에서 즉시 동기화한다. 취소·만료·실패 상한에는 대기 역할 정보도 제거한다.

콜백에 대기 state가 없거나 IdP error가 있으면 시도 상태만 소모하고 실패 횟수는 늘리거나 초기화하지 않는다. state 불일치·토큰 검증 실패·미연결 등 실제 검증 실패는 계속 누적한다. 반복된 무상태/IdP 취소 콜백 뒤에도 정상 시도 예약이 가능함을 검사했다. 삭제 후 같은 외부 subject를 새 계정에 연결하고 이전 로컬 ID는 재사용하지 않는 회귀도 추가했다.

이번 가짜 IdP·HTTP 회귀에는 외부 IdP 연결이 없다. 실제 IdP·HTTPS 프록시·NAS 실기·대규모 동시 로그인은 계속 미검증이다. 계정 판정 변경에 따른 초기 테스트 실패와 수정은 [계정 검증](accounts.md#인증-교차-검토-반영-기준-4b274c5)에 기록했다.

최종 lite/full 빌드·8104 런타임/lite 브라우저는 모두 종료 0이다. full 기준 전체 124건 실패/오류/생략 0, lite 브라우저 121개 화면·19개 검사와 서버 종료를 확인했다. 내부 식별자 무일치(`rg` 종료 1)·공백 검사 종료 0. [명령별 결과·실패 근거](accounts.md#인증-교차-검토-반영-기준-4b274c5), 상세 아티팩트 `build/verification/auth-review/`.
