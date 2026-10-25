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
