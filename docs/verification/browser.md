# 실제 브라우저 검증

기준 커밋 `ce94b82`. headless Chromium·Playwright를 사용하며 원본 샘플은 `./gradlew :core:generateSamples -q`로 생성한다. 서버는 `:app:bootRun`, 8104 루프백 HTTP, 임시 데이터는 `build/verification/browser/data`다. 스크린샷·로그·검증 의존성은 모두 무시되는 `build/verification/` 아래에 둔다. 인증값은 출력하지 않는다.

## 재현

시스템에 Python venv/pip가 준비된 경우:

```sh
python3 -m venv build/verification/venv
build/verification/venv/bin/python -m pip install playwright==1.64.1 mcp==2.3.0
PLAYWRIGHT_BROWSERS_PATH=build/verification/deps/browsers build/verification/venv/bin/python -m playwright install chromium
npm install --prefix build/verification/deps --cache build/verification/deps/npm-cache @axe-core/playwright@4.14.0
./gradlew :core:generateSamples -q
PLAYWRIGHT_BROWSERS_PATH=build/verification/deps/browsers build/verification/venv/bin/python scripts/verify-browser.py
```

pip/ensurepip가 없는 검증 환경에서는 공식 `https://bootstrap.pypa.io/get-pip.py`를 `build/verification/deps/`에 내려받고, 생성된 venv의 Python으로 실행해 준비했다. 시스템 패키지·저장소 의존성은 변경하지 않는다. 기존 `browser/data`가 있으면 스크립트는 덮어쓰지 않고 종료한다. 이전 결과를 다른 `build/verification/` 하위 폴더로 옮긴 뒤 실행한다. 빌드와 bootRun 검증은 순차 실행한다.

## 확인 범위

- 첫 설정·로그인, 일반/업로드 소스 등록·편집, 샘플 검색·문서 본문 보기.
- 업로드·폴더 생성·휴지통 이동·복원, 공개 공유·비밀번호 공유 HTTP Basic challenge와 Chromium 자동 자격 증명 응답, 공유 세션 미생성.
- TOTP 등록·활성화·로그아웃·TOTP 로그인, 복구 코드 표시 후 숨김.
- API 토큰 발급·숨김·철회, OAuth 관리 화면, 임베딩 off 저장/원격 입력, full off 설정, 정보·저장소·라이선스 링크.
- 브라우저 언어 `en-US`/`ko-KR` × 1280/390px × light/dark/auto. auto는 OS dark 모의. 전체 관리 화면의 스크린샷·axe WCAG A/AA·label 연결·가로 넘침·숨김으로 잘린 텍스트를 검사한다.
- Tab 키 이동·포커스 표시, 콘솔 예외/오류·실패 요청·HTTP 오류를 수집한다. 언어 전환은 기존 브라우저 언어 정책에 따른 새 컨텍스트로 확인한다.

## 발견·수정

- 첫 설정 보조 문구의 대비 부족: light/dark 보조 텍스트 색상을 조정했다.
- 390px 소스 관리 폼의 입력 요소 최소 너비 때문에 내부 가로 스크롤이 발생했다. label/fieldset/입력의 최소 너비와 자식 너비를 조정하고 내부 스크롤 컨테이너 검사도 추가했다.
- 입력 요소의 키보드 포커스 표시가 미약해 공통 `:focus-visible` 외곽선을 추가했다.
- full API는 있으나 관리 메뉴/화면이 없어 기존 스타일의 한/영 full 설정 화면을 추가했다. lite에서는 off만 선택하며 full 기능은 가용성에 따라 표시한다. 원격 키는 응답에 표시하지 않으며 주소 변경/키 삭제/평문 전송 정책은 기존 API를 따른다.

## 결과·한계

Playwright 1.64.1·Chromium 156.0.8078.4·axe-core 4.14.0을 사용했다.

| 실행 | 종료 코드 | 화면/접근성 검사 | 결과 |
| --- | --- | --- | --- |
| `scripts/verify-browser.py` (lite) | 0 | 101개 | 기능 흐름·96개 관리 화면 조합·공유/문서/첫 설정·axe 통과 |
| `VERIFY_FULL=true ... scripts/verify-browser.py` | 0 | 103개 | 위 검사 + 원격/내장 full 공급자 입력·OCR 필드, 내부 스크롤·Tab 포커스 표시 통과 |

두 실행 모두 콘솔 오류·실패 요청·비정상 HTTP 응답 0이다. 모든 화면의 label·잘림·가로 넘침·axe 위반 0이며, full 실행은 내부 스크롤 컨테이너까지 확장한 최종 검사다. full 관리 화면의 원격/내장 공급자 입력과 OCR 선택을 확인하되 실제 외부 서비스 호출/모델 추론은 실행하지 않았다. 정보 화면은 저장소 href·로컬 라이선스 HTTP 200을 확인했다. 공유는 익명 200/Basic 401 challenge→브라우저 자동 인증 200·실제 파일 다운로드·세션 쿠키 미생성을 확인했다.

아티팩트: `build/verification/browser/summary.json`, `build/verification/browser-full/summary.json`, 각 폴더의 `*.png`·`bootrun.log`, `build/verification/browser-run.log`·`browser-full-run.log`. 각 서버는 종료했고 8104 반환을 확인했다. full 실행은 새 데이터 `build/verification/browser-full/data`를 사용한다.

실패 이력도 보존한다. `browser-attempt-1/`은 샘플에 없는 검색어, `browser-attempt-2/`는 첫 설정 보조 텍스트 대비 부족, `browser-attempt-3/`은 색인 완료 전 단발 검색, `browser-attempt-4/`는 select의 wrapping label을 exact text로 찾은 스크립트 오류, `browser-attempt-5/`는 공개 페이지 CSP가 axe script 태그 주입을 차단한 경우다(각 종료 1). 검증기는 색인 대기·안정적인 select 탐색·Playwright 평가 컨텍스트 주입으로 수정했고 공개 CSP는 유지했다. `browser-full-attempt-1/`은 내부 가로 스크롤 검사가 발견한 모바일 폼 문제(종료 1)이며 CSS 수정 후 재실행이 통과했다. 초기 full UI 작성 중 TypeScript 오류도 수정 후 빌드/실행했다. 로그인·업로드 응답을 끝까지 읽어 Chromium의 `ERR_ABORTED` 기록도 해소했다.

`VERIFY_FULL=true`를 앞에 붙이면 같은 스크립트가 full bootRun을 띄우고 `browser-full/`에 결과를 남긴다. headless에서는 OS 네이티브 HTTP Basic 팝업의 시각적 모습, 스크린리더/실제 터치/실제 모바일 OS를 보증하지 않는다. 외부 저장소 링크는 href만 검사하며 원격 서비스에는 접속하지 않는다. 원격 임베딩·OCR·ONNX 실제 추론은 이 브라우저 검증 범위 밖이다. OAuth 승인 화면 자체의 1280/390·light/dark axe 검사도 별도 SDK 스크립트에서 통과했다.


## 최종 빌드·저장소 검사

- `./gradlew :core:generateSamples -q`: 종료 0, 원본 샘플 48개 생성.
- `./gradlew build -q`, `./gradlew -Pfull build -q`: 모두 종료 0. core 27·app 38·full 19개, 실패/오류/생략 0.
- `git diff --check`·두 Python 스크립트 `py_compile`: 종료 0.
- 내부 식별자/주소/경로 광역 검사에서 기존 보안 테스트의 합성 RFC1918 예제 1건이 잡혔다. 기준 커밋과 파일 전체가 동일함을 확인하고 이 예제만 제외한 최종 검사에서 일치 0건(`rg` 종료 1, 정상 무일치)이다. 실제 내부 정보는 발견되지 않았다. 커밋 메시지도 검사했다.
- 상세 명령/테스트 수: `build/verification/followup/build-results.json`, `lite-build.log`, `full-build.log`, `internal-scan-initial.json`, `internal-scan.json`.
- git add/commit/push·배포·LICENSE/NOTICE 변경은 수행하지 않았다. `.ai-commit-msg`에 한국어 메시지를 준비했다.

## 환경설정 통합·검색 결과 개선 (기준 11ed4c6)

- 임베딩/full 메뉴를 **환경설정 / Settings** 하나로 통합했다. 검색 설정은 기존 `/api/embeddings`, 리랭커·OCR은 기존 `/api/full` 저장 흐름을 사용한다.
- lite에서는 리랭커·OCR 섹션과 내장 임베딩 선택지가 DOM에 없음을 검사한다. full에서는 원격/내장 리랭커·OCR 입력을 검사한다. 현재 빌드는 정보 화면에서만 작게 표시한다.
- 실제 샘플 검색·문서 열기로 API용 내부 경로 유지와 표시용 소스 이름·상대 경로를 검사한다. 고정 검색 응답으로 늦은 위치의 영문/한글 다중 토큰·대소문자 무시 강조·360자 발췌·앞뒤 생략·공백 축약·펼치기/접기·일치 없는 질의·HTML 문자열의 텍스트 처리를 검사한다. REST·MCP 응답 형식은 변경하지 않았다.
- 초기 lite 브라우저 검증 2회는 신규 고정 응답 검증에서 종료 1이었다. 기존 여러 검색 결과가 단일 고정 결과로 교체되기 전에 locator를 검사한 것이 원인이다. 결과 수 대기를 추가했으며 실패 줄 번호만 기록해 인증값 없이 원인을 추적한다. 증거는 `build/verification/browser-settings-attempt-1/`, `browser-settings-attempt-2/`에 보존했다.
- 새 화면 조합은 7개 메뉴 × 한/영 × 1280/390 × light/dark/auto이며, 별도 발췌·전체 청크 스크린샷과 axe 검사도 포함한다. 외부 모델 추론·실제 OCR/Qdrant 호출·NAS 실기 화면은 이 검증 범위에 포함하지 않는다.

- 최종 결과: `./gradlew build -q`·`./gradlew -Pfull build -q` 각각 종료 0, lite 브라우저 91개/full 93개 화면 각각 종료 0. axe 위반·미실행·콘솔 오류·실패 요청·HTTP 오류 모두 0, 두 서버 종료·8104 반환 확인.
- `python3 -m py_compile scripts/verify-browser.py`·`git diff --check` 종료 0. 추적 파일·신규 컴포넌트·커밋 메시지의 내부 식별자·주소·경로 grep 무일치(`rg` 종료 1).
- 아티팩트: `build/verification/settings-results.json`, `settings-build-lite.log`, `settings-build-full.log`, `settings-browser-lite.log`, `settings-browser-full.log`, `settings-internal-scan.json`, `browser/summary.json`, `browser-full/summary.json`, 각 브라우저 폴더의 PNG·bootrun.log.

## 관리 화면 2차 개선 (기준 47e2df5)

사이드바는 소스·파일/공유·API 토큰·검색을 유지하고 하단에는 설정 톱니바퀴와 자동/라이트/다크 세그먼트를 배치했다. 중앙 환경설정은 계정 → 2단계 인증 → 검색 → full 전용 리랭커·OCR → 정보 → 로그아웃 순서다. 라이선스는 별도 중첩 모달에서 기존 정적 파일을 가져오며 원본 링크를 유지한다. 모바일은 전체 화면이다.

계정 변경은 현재 비밀번호, 활성화 시 미사용 TOTP 코드, 별도 시도 제한을 요구한다. 성공하면 영속 세대 번호를 증가시키고 현재 세션 ID·CSRF를 교체한다. 다른 관리자 세션과 TOTP 대기 세션은 다음 요청에서 무효화한다. 사용자명은 최초 설정과 같은 ASCII 영문·숫자·_.- 3~100자 규칙을 유지했다. 폐기·만료 토큰의 행 삭제는 감사 기록을 유지하고 삭제를 기록하며 활성 토큰은 409로 거부한다.

검증 범위:

- 실제 HTTP 회귀: 세션/CSRF 거부, 현재 비밀번호 오류·시도 제한, 비밀번호 정책·확인 불일치·사용자명 경계, ID/CSRF 교체·이전 세션/코드 대기 무효·변경 후 로그인, 활성 TOTP 요구·복구 코드 거부, 활성 토큰 삭제 409·개별/일괄 삭제·감사 보존·삭제 전후 다운로드 티켓 거부.
- Chromium: 계정 변경 후 재로그인, 토큰 개별/일괄 삭제, 설정·라이선스 모달, Esc/바깥 클릭/닫기, 중첩 닫기·포커스 복귀·Tab 순환, 한/영 × 1280/390 × 자동/라이트/다크, lite/full 공급자 가용성. axe가 없으면 검증을 실패시킨다.
- 실제 모바일·스크린리더·별도 브라우저·외부 HTTPS는 미검증이며 기존 운영 검증 한계는 유지한다. 다중 관리자(역할 포함)는 후속 단계다.

초기 실패와 수정:

- Gradle 빌드와 테스트를 잘못 병행해 `EOFException` 및 테스트 결과 임시 파일 누락으로 각각 종료 1. 이후 빌드·테스트·서버 검증을 순차 실행했다.
- 로그인 선택자가 계정 변경 폼의 사용자명까지 선택해 첫 브라우저 실행 종료 1. 로그인 화면으로 범위를 제한하고 토큰 화면 재로드 후 이동을 명시했다.
- 중첩 라이선스의 Esc가 부모까지 닫아 브라우저 실행 종료 1. cancel 전파를 차단했다. 다음 실행에서 첫 컨트롤의 Shift+Tab 순환 검증이 실패(종료 1)해 명시적 Tab 순환을 보완했다. 중첩 모달을 동시에 해제할 때 스크롤 잠금이 남지 않도록 열린 dialog를 기준으로 CSS에서 잠근다.

- 첫 full 브라우저 실행은 TOTP 상태 조회 전에 비밀번호를 입력해 활성 폼 전환 때 입력이 비워져 종료 1. 해제 버튼 표시(서버 상태 조회 완료)를 기다린 뒤 입력하도록 검증 스크립트를 보완했다.

최종 결과와 명령별 종료 코드는 로컬 `build/verification/admin-ui/report.txt`, 전체 테스트 합계는 같은 폴더의 `tests-full.json`에 기록한다. 화면별 axe·레이아웃·오류 결과와 스크린샷은 `build/verification/browser/`, `build/verification/browser-full/`에 보존한다. 실패 화면/요약은 `build/verification/browser-admin-attempt-1/`부터 `-3/`까지 보존한다. 인증값은 보고와 로그에 기록하지 않는다.

최종 결과: `./gradlew build -q`, `./gradlew -Pfull build -q`, `VERIFY_PORT=8104 python3 scripts/verify-runtime.py`, 브라우저 lite 및 `VERIFY_FULL=true` 모두 종료 0. core 28·app 64·full 21개 테스트 실패·오류·생략 0. lite 93/full 95개 화면 모두 axe 위반·브라우저 오류 0, 서버 종료·8104 반환 확인. 런타임 최대 RSS 280.1 MiB이며 강제 컨테이너 메모리 상한 실측은 아니다. 내부 정보 검사 일치 0건(`rg` 종료 1)·공백 검사 종료 0. 첫 full 브라우저 실패 자료는 `build/verification/browser-full-admin-attempt-1/`에 보존한다.

## 관리 화면 스크롤바 숨김 (기준 9ca8a6c)

기본 스크롤바는 Firefox의 `scrollbar-width`/`scrollbar-color`와 Chromium·Safari의 `::-webkit-scrollbar`로 공간 없이 숨긴다. 호버·포커스 영역의 위치·스크롤 비율을 `frontend/src/scrollbars.ts`에서 읽어 6px 반투명 오버레이를 겹쳐 표시한다. 기본 스크롤바 폭을 전환하면 일반 스크롤바를 사용하는 OS에서 레이아웃이 이동하므로 별도 표시를 사용했다. `scrollbar-gutter`·기존 overflow·콘텐츠 크기는 변경하지 않는다. 강제 색 모드에서는 이 스타일과 오버레이를 적용하지 않아 기본 스크롤바를 유지한다.

오버레이는 양방향 드래그와 그 위의 휠 입력을 지원하며, 내용 영역의 휠·터치·키보드 스크롤은 브라우저가 처리한다. 보조 표시를 접근성 트리와 Tab 순서에서 제외하고 기존 스크롤 영역의 포커스를 유지한다. 중첩 모달은 최상위 모달에만 표시하여 비활성 배경에 스크롤바가 남지 않게 한다.

`scripts/verify-browser.py`에 일반 영역·pre·textarea의 기본 공간 0, 호버/포커스 표시, 6px 너비, 내용 크기 불변, 가로/세로 휠, 키보드, 드래그, 테마 반투명 색, 강제 색 기본 복귀 검사를 추가했다. Chromium CDP 터치 스와이프 모의 입력과 실제 라이선스 모달의 키보드 스크롤·배경 표시 제거도 검사한다.

실패 이력: 첫 브라우저 실행은 초기 설정 페이지의 실제 페이지 스크롤바까지 테스트용 영역과 함께 집계하여 종료 1이었다. 테스트 중 페이지 스크롤을 분리했고 overflow가 잠긴 페이지의 표시는 제외했다. 증거는 `build/verification/scrollbars/browser-attempt-1/`에 보존했다. 수정 후 전체 브라우저 검증 종료 0을 확인했고, 스크린샷에서 발견한 비활성 배경 표시까지 보완하여 최종 재검증했다.

검증 한계: Firefox 실행 파일은 Snap 사용자 데이터·프로세스 격리 제약으로 `firefox --version`부터 종료 46이었다(`build/verification/scrollbars/firefox-check.log`). Safari·실제 OS 기본 스크롤바·실제 터치 기기·스크린리더는 이 환경에서 검증하지 못했다. full 빌드·외부 서비스 검증은 이번 공통 화면 스타일 변경 범위에서 실행하지 않았다. lite 빌드의 full 공급자 전용 OCR 통합 테스트 2건은 구성 조건으로 생략된다.

최종 결과: `./gradlew build -q` 종료 0, lite 브라우저 93개 화면 종료 0, axe 위반·브라우저 오류 0, 8104 서버 종료·포트 반환 확인. core 31·app 65·full 모듈 21건 중 실패/오류 0, app의 위 2건만 생략했다(full 앱 구성 빌드는 수행하지 않았다). `python3 -m py_compile scripts/verify-browser.py`·`git diff --check` 종료 0, 추적 파일·신규 코드·커밋 메시지 내부 정보 검사 무일치(`rg` 종료 1). 상세 명령·결과는 `build/verification/scrollbars/report.json`, 빌드/브라우저 로그는 같은 폴더의 `build.log`·`browser.log`, 화면별 결과·스크린샷은 `build/verification/browser/`에 있다.
