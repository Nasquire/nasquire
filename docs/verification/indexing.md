# 색인 주기·지원 형식 검증

기준: `1048a55`. 기본 검사 5분, 안정화 대기 2초를 유지한다. 관리자 저장 범위는 주기 1~60분·파일 1~512 MiB이며 Office/HWP 원본 8 MiB 제한은 별도다.

## 검증 범위

- `IndexingSettingsIntegrationTest`: 관리자 인증·헤더 CSRF, 주기/크기 경계 거부, 저장 파일 재로딩 시 환경 기본값보다 우선, 감사, 소스별 검사 API, lite/full 지원 확장자 차이.
- `IndexingControlsTest`: 크기 초과 7건·최근 상대 경로 최대 5개·오류 0건, 상한 증가 후 다음 검사에서 7개 색인/검색, 작업 중 100개 요청을 다음 한 번으로 병합, 긴 주기에서 재스케줄 즉시 실행.
- `verify-browser.py`: 빈 glob·빌드별 지원 확장자·지금 검사·환경설정 저장과 기존 한/영·모바일·테마·접근성 흐름.
- `verify-runtime.py`: 짧은 검사 주기를 초기 설정으로 명시해 기존 48문서 검증을 수행한다.

## 초기 실패와 수정

- 기본 빌드 첫 시도는 기존 Node 캐시의 파일 권한으로 종료 1. 시스템 Node를 사용한 진단 재시도도 기존 앱 산출물 정리 권한으로 종료 1. 기존 산출물은 무시되는 `build/verification/indexing-previous/`로 옮겨 보존한 뒤 원래 명령으로 다시 생성했다.
- 새 산출물 빌드에서는 기존 전역 POI 임시 폴더 권한으로 Office 테스트 2개 실패(종료 1). JVM 임시 폴더를 무시되는 작업 폴더 안으로 지정했다.
- 다음 빌드에서 종료 중 Lucene I/O 인터럽트로 신규 검사 테스트 1개 실패(종료 1). 작업자 종료를 정상 대기 후 시간 초과 시 중단하는 순서로 수정했다.

## 한계

실제 Synology x86 NAS·NAS HDD 절전 진입·대용량 폴더 검사 시간·NFS/SMB 지연·512 MiB에서 최대 파일 크기 조합은 실측하지 않았다. 큰 파일 허용이 추출 성공을 보장하지 않으며 Office/HWP·OCR의 독립 제한은 계속 적용한다. 새 파일은 정기 검사나 지금 검사로 최초 관측한 뒤, 주기와 무관한 후속 검사에서 안정화 대기를 확인하여 색인된다. full 이미지 지원 목록은 OCR off에서도 보이며 실제 이미지 추출은 OCR 활성화가 필요하다.

최종 결과와 로컬 아티팩트는 아래와 같다.

## 빌드·런타임 결과

- `./gradlew build -q`: 종료 0. core 30·app 65건, 실패/오류 0. lite에서 full OCR 전용 2건은 조건부 생략되며 아래 full 실행에서는 모두 통과했다.
- `./gradlew -Pfull build -q`: 종료 0. core 30·app 65·full 21건, 실패/오류/생략 0.
- `VERIFY_PORT=8104 python3 scripts/verify-runtime.py`: 종료 0. 기존 짧은 주기 명시 설정으로 문서·업로드·공유 스모크 성공, 앱과 bootRun 종료·8104 반환 확인. 이번 앱 JVM 최대 RSS 280.4 MiB이며 컨테이너 전체 메모리 측정은 아니다.
- 위 명령은 기존 공용 임시 폴더 권한 충돌을 피하기 위해 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<작업폴더>/build/verification/indexing-tmp`를 지정했다. 빌드 플러그인이나 보안 설정을 우회하지 않았다.

로컬 상세: `build/indexing-build.log`, `build/indexing-full-build.log`, `build/verification/indexing-controls/`, `build/verification/measurement.json`, `build/verification/browser/`, `build/verification/browser-full/`.

- lite 브라우저: 종료 0, 화면 93개·기능 체크 15개, axe 위반·브라우저 오류·실패 요청 0. 서버 종료와 8104 반환 확인.
- 내부 정보 검사: 소스·README·CLAUDE·docs·scripts의 내부 식별자/도메인/계정/경로 및 토큰 접두사 `rg` 검사 무일치(종료 1). `git diff --check` 종료 0.

- full 브라우저: 종료 0, 화면 95개·기능 체크 15개, axe 위반·브라우저 오류·실패 요청 0. 서버 종료와 8104 반환 확인.

## 긴 주기에서 안정화 대기 복구 수정 (기준 f1a0e17)

- 안정화 대기 stamp가 있는 소스만 가장 빠른 안정화 시각+25ms에 같은 단일 작업자로 후속 검사한다. 소스별 예약은 하나로 병합하고 외부 요청/정기 검사마다 연속 횟수를 초기화한다. 최대 10회 뒤에는 정기 검사나 명시 요청까지 기다린다. 계속 변하는 파일도 다른 파일의 추출을 막지 않는다.
- 시작·주기 변경은 즉시 전체 검사, 소스 저장·재색인·OCR 설정 재색인은 즉시 해당 소스 검사다. 전체 벡터 재색인 API도 즉시 검사를 요청한다. 전체 재구축을 시작한 검사는 모든 소스를 관측하고 소스별 후속 검사 완료 상태를 모아 재구축을 끝낸다.
- 동일 정의의 소스 재색인은 기존 문서를 유지하며 안정화·추출이 끝난 파일만 replace한다. 삭제·제외·크기 초과 파일 정리는 유지한다. 시작/루트·glob 변경은 이전 범위 문서를 남기지 않도록 기존 소스 삭제 경로를 유지한다. 전체 벡터 재구축도 모델·세대 일관성 때문에 기존 삭제를 유지한다. 이 경우 안정적인 소규모 파일은 약 2초 뒤 다시 검색 가능하다. 대용량 추출·느린 I/O·추출 오류에는 수 초 복구를 보장하지 않는다.
- 파일 신규 생성 자체를 감지하는 감시자는 추가하지 않았다. 최초 발견은 정기 검사 또는 지금 검사이며, 이번 수정은 발견 뒤 안정화 때문에 한 주기를 더 기다리는 문제를 해소한다.
- 회귀 테스트는 1시간 주기·실제 2초 안정화에서 시작/추가/재색인/전체 재구축/삭제/재시작을 검증한다. 별도 변동 파일 테스트는 안정 파일 검색과 후속 검사 정확히 10회 뒤 정지 및 수동 요청 후 재개를 검증한다.
- 브라우저 검증은 업로드 소스가 없는 한/영 화면에서 안내·빈 선택 상자 제거·소스 관리 이동을 검사한다. 일반 소스의 읽기 전용 설명을 유지한다.

### 이번 빌드·실측 결과

- `./gradlew :core:test -q`: 종료 0. 이후 전체 빌드에서 추가 다중 소스 테스트까지 포함하여 core 34건 통과.
- `./gradlew build -q`: 종료 0. core 34·app 65건, 실패/오류 0, lite의 full 전용 2건 생략.
- `./gradlew -Pfull build -q`: 종료 0. core 34·app 65·full 21건, 실패/오류/생략 0.
- `VERIFY_PORT=8104 python3 scripts/verify-runtime.py`: 종료 0. 48문서 REST/MCP·16 MiB 업로드·공유·TOTP 스모크 통과, 앱/bootRun 종료와 포트 반환 확인. 앱 JVM 최대 RSS 271.1 MiB이며 컨테이너 전체 메모리는 미측정.
- 위 빌드와 실행은 기존 공용 임시 폴더 충돌을 피하도록 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<작업폴더>/build/verification/indexing-tmp`를 사용했다. 브라우저는 기존 `build/verification/venv`와 Chromium·axe를 사용했으며 패키지를 설치하지 않았다.
- lite 브라우저 첫 실행은 종료 1. 새 이동 검증이 소스 화면 제목을 메뉴 이름으로 잘못 기대했다. 실제 제목으로 테스트를 수정했다. 최초 실패의 `summary.json`(실패 줄 271)과 로그는 보존했다. 제품의 안내·이동 동작에는 수정이 필요하지 않았다.
- 내부 정보 `rg` 검사 무일치(종료 1), 별도 Python 검사 종료 0. `git diff --check` 종료 0.

아티팩트: `build/indexing-fix-{core,build,full-build,runtime,browser}.log`, `build/verification/indexing-fix-results.json`, `build/verification/measurement.json`, `build/verification/browser/summary.json`, `build/verification/indexing-private-{scan,grep}.txt`. 최초 브라우저 실패는 `build/indexing-fix-browser-first-failure.log`, `build/verification/browser-stability-first-failure/`에 보존했다.

실제 NAS/NFS/SMB·대용량/느린 추출·full OCR 품질/부하는 이번에 추가 검증하지 않았다. 기존 full 테스트와 빌드 통과는 이 환경의 단위/통합 검증 범위다.

- lite 브라우저 재실행: 종료 0. 화면 95개·기능 체크 17개, axe 위반·브라우저 오류·실패 요청 0. 업로드 소스 없음 한/영 안내·소스 관리 이동 통과, 서버 종료·8104 반환 확인.
