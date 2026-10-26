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

실제 Synology x86 NAS·NAS HDD 절전 진입·대용량 폴더 검사 시간·NFS/SMB 지연·512 MiB에서 최대 파일 크기 조합은 실측하지 않았다. 큰 파일 허용이 추출 성공을 보장하지 않으며 Office/HWP·OCR의 독립 제한은 계속 적용한다. 새 파일은 최초 관측 후 안정화 대기를 충족한 다음 검사에서 색인된다. full 이미지 지원 목록은 OCR off에서도 보이며 실제 이미지 추출은 OCR 활성화가 필요하다.

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
