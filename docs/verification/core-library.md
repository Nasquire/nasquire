# core 라이브러리 검증

기준은 `d93f5eb`이다. 실행 로그·초기 실패 XML·로컬 Maven 산출물 검사는 무시되는
`build/verification/core-library/`에 저장한다. 원격 게시는 수행하지 않는다.

## 변경과 판단

- 공개 루트 패키지 API를 유지하고 `NasquireCore`, `AccessScope`, 원본 opener와 이벤트를 추가했다.
  메모리 회수 구현은 `.internal`로 옮겼다. JPMS를 보류한 이유는 [사용 안내](../core-library.md)에 적었다.
- Lucene는 기존 `key`를 사용한다. Qdrant는 `access.format=2`로 재구축하고 이전 형식의 재시작·중간 refresh도 검증한다.
- 이벤트 버전 키는 내용 해시가 아닌 `size:mtime`이다. 호출 앱 DB와의 원자성·영속 전달은 보장하지 않는다.
- 의존성은 바꾸지 않아 THIRD-PARTY는 변경하지 않았다.

## 초기 실패와 수정

- `./gradlew :core:test -q`: 종료 1, 39건 중 1건 실패.
  `IndexingControlsTest.selectedRebuildCompletesAllSourcesAndCleansChangedScope`에서 삭제된 소스의 상태가
  Lucene refresh 전에 사라져 완료로 판단한 직후의 검색에 이전 문서가 남았다.
  삭제 상태 공개를 refresh 뒤로 옮겼다. 로그는 `initial-core-test.log` / `initial-failure.xml`이다.
- 최초 `./gradlew -Pfull build -q`: 종료 1, core 42건 중 1건 실패.
  `CoreLibraryExampleTest.configuredVectorsBuildOnFirstScan`에서 “공급자 또는 재색인 필요” 예외가 발생했다.
  빌더에서 설정 변경 시 `requestRebuild`를 호출해 첫 scan에서 구축을 시작하도록 수정했다.
  로그는 `initial-full-build.log` / `initial-full-failure.xml`이다.
- 수정 API 한정 검증: `./gradlew :core:test --tests '*CoreLibraryExampleTest' --tests '*AccessScopeTest' -q` 종료 0.

- 최종 전체 재검증 첫 시도도 종료 1(core 43건 중 1건 실패): 추가한 Qdrant 후보 제한 테스트의
  접두사 `a:b`가 드라이브 경로 거부 규칙에 걸렸다. 실제 허용되는 `more:dir`로 테스트 입력을 고쳤다.
  운영 코드의 검증 규칙은 유지했다. 근거는 `prefix-fixture-failure.log` / `prefix-fixture-failure.xml`이다.

## 최종 검증

| 명령 | 종료 코드·결과 |
| --- | --- |
| `./gradlew -Pfull build -q` | 0; core 43·app 69·full 21건, 실패/오류/생략 0 |
| `./gradlew build -q` | 0; `:app:verifyLiteClasspath` 포함, app의 full 전용 2건은 예상대로 생략 |
| `./gradlew :core:publishToMavenLocal -Dmaven.repo.local="$PWD/build/maven-local" -q` | 0; 홈 대신 무시되는 빌드 폴더에 게시 |
| `python3 build/verification/core-library/check-publication.py` | 0; 좌표·binary/sources/Javadoc·POM 라이선스/SCM·fixture 제외 확인 |
| `VERIFY_PORT=8104 python3 scripts/verify-runtime.py` | 0; 48문서·REST/MCP·다운로드·업로드/공유·TOTP 검증, 최대 RSS 273.9 MiB |
| `PLAYWRIGHT_BROWSERS_PATH=build/verification/deps/browsers build/verification/venv/bin/python scripts/verify-browser.py` | 0; lite 121화면·19검사·axe/브라우저/HTTP 오류 0 |
| 내부 정보 `rg` | 1; 추적 파일·신규 파일·커밋 메시지에서 내부 식별자/경로 무일치 |
| `git diff --check` | 0 |

최종 명령별 결과는 `build/verification/core-library/final-results.json`, full 테스트 집계는
`full-tests.json`에 남겼다. 런타임·브라우저는 순차 실행했고 둘 다 서버 종료·8104 반환을 확인했다.
브라우저 상세는 `build/verification/browser/summary.json`, 런타임 측정은 `build/verification/measurement.json`이다.
최종 내부 정보·공백·포트 검사는 `build/verification/core-library/final-checks.log`에 남긴다.
로컬 Maven 산출물은 `build/maven-local/com/bluesounds/nasquire/nasquire-core/0.1.0/`이다.

원격 게시·full 브라우저·별도 컨테이너/cgroup·실제 Qdrant/NAS 검증은 이번에 수행하지 않았다.
원격 게시는 작업 지시로 금지되어 있고, 나머지는 이번 완료 기준 밖이다. 기존 라이선스 파일은 수정하지 않았다.

## 범위 밖·남은 제약

실제 Qdrant 서버·NAS/NFS/SMB·외부 앱 운영 도입·원격 Maven 게시는 미검증이다.
Qdrant는 FakeQdrant를 통한 REST payload·후보 제한 전 권한 적용·불신 응답 재검사까지 확인한다.
영속 이벤트 전달·JPMS exports는 제공하지 않는다. 그룹 정책 후속 구현은 아래 기록을 따른다.

## 사용자·그룹 정책 후속 (기준 2e1f8f2)

- 기본 open은 등록된 모든 소스를 Principal 없이 검색·조회한다. rule은 현재 Principal과 등록 소스만 받아
  불변 AccessScope로 컴파일한다. 그룹 저장소·DB·Spring 의존성은 추가하지 않았다.
- 접두사별 결정을 보존하여 가장 긴 접두사·동률 DENY·기본 거부·하위 재허용을 구현했다.
  기존 생성자·sources 팩토리·앱의 명시적 AccessScope 경로는 유지한다.
- 공통 허용 영역을 Lucene 자동자/소스 필터와 Qdrant payload must_not/should 필터로 변환한다.
  후보 제한 전에 적용하고 외부 응답·RRF·최종 결과·문서 조회에서 다시 검사한다.
  리랭커 호출은 기존대로 검사된 검색 결과 이후다.
- 규칙 4096개, 컴파일 접두사 1024개, Principal 그룹 256개·ID 256 UTF-16 단위 상한을 두었다.
  정책은 volatile로 교체하며 다음 요청부터 적용한다. 진행 중인 요청은 취득한 정책을 사용한다.
- 판정 표·여러 그룹 충돌·everyone·디렉터리 경계·상한·1024 접두사 실제 검색·FakeQdrant 불신 응답과
  BM25/HNSW/RRF/문서 조회·open/rule 실행 예제·재색인 없는 교체를 검증한다.

초기 `./gradlew :core:test -q` 두 실행은 종료 1이었다. 작업자가 검증을 중첩 실행하여 공유하는
테스트 결과 파일에서 EOFException 및 NoSuchFileException이 발생했다. 이후 검증은 순차 실행한다.
근거는 `build/verification/access-policy/initial-core.log`, `overlapping-core.log`에 보존했다.

| 최종 명령 | 종료 코드·결과 |
| --- | --- |
| `./gradlew -Pfull build -q` | 0; core 48·app 69·full 21건 실패/오류/생략 0 |
| `./gradlew build -q` | 0; lite 예상 app 생략 2건, 실패/오류 0 |
| `./gradlew :app:verifyLiteClasspath -q` | 0; full 의존성 유입 없음 |
| `./gradlew :core:publishToMavenLocal -Dmaven.repo.local="$PWD/build/maven-local" -q` | 0; 로컬 게시 |
| `VERIFY_PORT=8104 python3 scripts/verify-runtime.py` | 0; 48문서·기존 REST/MCP·권한 동작, 최대 RSS 273.2 MiB |
| `PLAYWRIGHT_BROWSERS_PATH=build/verification/deps/browsers build/verification/venv/bin/python scripts/verify-browser.py` | 0; lite 121화면·19검사, axe/브라우저/HTTP 오류 0 |
| 내부 식별자·경로 `rg` | 1; 추적/신규 파일·커밋 메시지 무일치 |
| `git diff --check` | 0 |

두 실행 검증은 8104만 사용했고 서버 종료·포트 반환을 확인했다.
명령별 로그·결과는 `build/verification/access-policy/`의 `results.json`, `full-tests.json`,
`full-build.log`, `lite-final.log`, `publication.log`, `runtime.log`, `browser.log`, `final-checks.json`에 있다.
브라우저 상세는 `build/verification/browser/summary.json`, 런타임 상세는
`build/verification/access-policy/runtime-measurement.json`이다.
전체 주소 범위 추가 스캔에서 기존 URL 검증 테스트의 일반 사설 IP 예시 1건이 발견되어
조직 내부 식별자 검사와 구분했다. 신규 내부 정보는 없다.

실제 Qdrant/NAS·별도 컨테이너/cgroup·full 브라우저는 완료 기준 밖이므로 실행하지 않았다.
외부 VectorStore는 권한 필터 지원 구현이 필요하며 기본 구현은 제한 요청을 거부한다.
인증·최신 멤버십 전달은 호출 앱의 책임이다. 원격 게시·git add/commit/push는 지시대로 하지 않았다.
LICENSE·LICENSE-EXCEPTIONS.md·NOTICE와 앱 인증/REST/MCP 형식은 변경하지 않았다.
