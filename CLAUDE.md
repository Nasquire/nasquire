# Nasquire 지침
> 공개 저장소다. 코드·문서·커밋에 내부 주소·도메인·서버 이름·계정·비밀값·사내 경로를 넣지 않는다. 예시는 `example.com`, `/data`, `/sources/...`처럼 일반 값만 쓴다.

관리자 최초 설정·오프라인 복구 비밀번호는 8자 이상, UTF-8 72바이트 이하이며 영문자(A-Z/a-z)·숫자(0-9)·출력 가능한 ASCII 문장부호를 각각 하나 이상 포함한다. 대문자·소문자를 각각 강제하지 않는다. 로그인·TOTP의 기존 비밀번호 확인과 공유 비밀번호에는 이 새 정책을 적용하지 않는다.

## 개요
- 기존 폴더를 읽기 전용으로 색인하는 단일 컨테이너 검색 서비스("Ask your NAS").
- 일반 소스 원본에는 절대 쓰지 않는다. 선택 업로드 소스(`kind=upload`)만 별도 쓰기 가능 폴더를 사용한다. 모든 소스와 데이터 폴더 중첩 금지, 업로드 소스는 다른 소스와도 중첩 금지.
- Java 25 toolchain, Gradle 9.8.1, Spring Boot 4.1.2. 최저 목표는 512 MiB 컨테이너.

## 구조
- `core/src/main/java/com/bluesounds/nasquire/core`: Source·glob, 추출기(PDFBox·텍스트/ICU·POI 이벤트 Excel/Office·hwplib/hwpxlib), 단일 저우선순위 주기 검사, Lucene Nori/BM25, 선택 공급자 인터페이스·원격 임베딩·HNSW/RRF. ONNX 구현/의존성은 full에 둔다.
- `full/src/main/java`: 선택 ONNX 임베딩·원격 리랭커·Tesseract OCR. `Providers.FullProvider`/ServiceLoader로 연결하며 기본 app 런타임에는 포함하지 않는다.
- `app/src/main/java/com/bluesounds/nasquire/app`: 업로드 전용 WritableSource·SecureDirectoryStream/Linux mkdirat·renameat2, 해시 외부 공유·별도 stateless 보안 경로, Spring MVC REST, H2/JDBC, BCrypt 관리자 세션·CSRF, 해시 API 토큰, 감사 로그, 공식 MCP SDK의 stateless Streamable HTTP(`/mcp`), 메모리 불투명 티켓 다운로드 주소(`/api/download`, `/download`).
- `frontend/src`: React·Vite·TypeScript 관리 화면(브라우저 한/영, 자동/라이트/다크). Gradle이 Node를 내려받고 npm ci로 빌드하여 app의 static에 포함한다.
- `frontend/public/icon.svg`: 시그니처 아이콘 복사본. 원본 자산은 수정하지 않는다.
- `Dockerfile`: 다단계 `lite`(기본)·`full` 비루트 JRE, OCR 도구·헬스체크·OCI 라벨. `deploy/docker-compose*.yml`: lite/full/ML, 숫자 사용자 매핑·읽기 전용 소스·별도 업로드·데이터 볼륨. `deploy/unraid/`, `docs/install/`: NAS 템플릿·설치/백업/복구 안내. `.github/workflows/`: 공식 Linux amd64(64비트) lite/full CI와 태그 전용 GHCR 게시(액션 SHA 고정).
- `scripts/smoke.py`: 새 서버 데이터에 대한 curl 실제 API 검증(인증값 미출력).
- `scripts/verify-browser.py`: Playwright Chromium 실제 화면·한/영·모바일·테마·axe 스모크. 선택 `VERIFY_FULL=true`는 full 설정 공급자 화면도 검사한다. 도구·임시 데이터·스크린샷은 `build/verification/`에만 둔다.
- `scripts/verify-oauth-sdk.py`: 공식 Python MCP SDK OAuth·Streamable HTTP와 브라우저 승인·자동 갱신·철회 검증. 재현·한계는 `docs/verification/browser.md`, `docs/verification/oauth.md`. 두 스크립트는 8104 전용이며 다른 bootRun/빌드와 순차 실행한다.
- `scripts/verify-runtime.py`: 문서 생성·bootRun·curl·RSS·서버 종료를 자동 수행. 결과는 무시되는 `build/verification/`에 생성한다.
- `scripts/measure-memory.py`: lite bootJar의 자체 생성 큰 PDF 18개·Office 색인 RSS/NMT·공유 cgroup anon/file 비교. `docs/verification/memory.md`의 옵션으로 실행하며 결과·원본은 `build/verification/memory/`에만 둔다. 빌드·다른 런타임 검증과 순차 실행한다.
- `scripts/fetch-verification-models.py`: 명시적 내장 모델 검증 시 고정 리비전·SHA-256 모델 파일을 무시되는 build 폴더에 준비한다.
- `docs/embeddings.md`, `docs/verification/vector.md`: 공급자·재색인·모델 출처·아키텍처 중립 구현·실측 제한. arm64는 공식 이미지·지원 없이 직접 빌드만 가능하며 이후 변경은 미검증이다.
- `docs/ROADMAP.md`, `docs/verification/mvp.md`, `THIRD-PARTY.md`: 진행·실측·의존성 라이선스.
- `CONTRIBUTING.md`·`SECURITY.md`: 한/영 기여·보안 정책. 외부 코드·문서·번역은 받거나 반영하지 않으며 이슈는 버그·기능 아이디어만 받는다. `.github/ISSUE_TEMPLATE/`과 PR 안내를 유지한다. `close-external-prs.yml`은 OWNER/MEMBER/COLLABORATOR 외 PR을 안내 후 닫는다. `pull_request_target`에서 PR 코드를 체크아웃·실행하지 않고 `pull-requests: write`만 부여하며 액션 SHA를 고정한다.

## 명령
- 빌드·테스트(기본 lite): `./gradlew build -q` (`:app:verifyLiteClasspath`가 full/ONNX/DJL 유입 검사)
- full 선택 빌드·실행: `./gradlew -Pfull build -q`, `./gradlew -Pfull :app:bootRun`; 공급자 테스트 `./gradlew :full:test -q`. 앱 JAR 경로는 같으므로 마지막 빌드 구성에 유의한다. Dockerfile의 기본 타깃도 lite다.
- 코어/통합 테스트: `./gradlew :core:test :app:test -q` (가짜 Qdrant는 core testFixtures `FakeQdrant`)
- 실행: `./gradlew :app:bootRun` (포트 8080, 저장소 루트 `./data`)
- 별도 설정: `./gradlew :app:bootRun --args='--server.port=8101 --nasquire.data=./data'`
- 자체 작성 문서 48개 생성(PDF·Markdown 40개와 Office/HWP 8개): `./gradlew :core:generateSamples -q` (`build/samples`)
- Linux 실측: `VERIFY_PORT=8101 python3 scripts/verify-runtime.py` (Python 3·curl 필요, 새 임시 DB, 종료 자동 처리)
- 벡터 실측: `VERIFY_PORT=8101 VERIFY_EMBEDDING=builtin python3 scripts/verify-runtime.py` (모델 다운로드·해시 검사 포함). `remote`는 가짜 HTTP 공급자 검증. 빌드와 bootRun 실측은 클래스 출력 폴더를 공유하므로 동시에 실행하지 않는다.
- 컨테이너: `DATA_DIR=/path/to/data SOURCE_DIR=/path/to/documents docker compose -f deploy/docker-compose.yml up --build -d` (호스트 데이터 권한·HTTPS 프록시 선행). full은 `-f deploy/docker-compose.full.yml` 추가, ML은 `-f deploy/docker-compose.ml.yml` 독립 실행. 직접 빌드는 `docker build --target lite .` / `docker build --target full .`; 검증 한계는 `docs/verification/deploy.md`.

## 구현 규칙
- AGPL 호환 의존성만 사용한다. 라이선스 변경 시 `THIRD-PARTY.md`를 갱신한다. LICENSE·LICENSE-EXCEPTIONS.md·NOTICE는 임의 변경하지 않는다.
- 저작권 `Copyright (c) 2026 Bluesounds`, 정보 화면 저장소·라이선스 링크를 유지한다.
- 계정·토큰 원문을 로그·DB에 남기지 않는다. 토큰은 읽기 전용, DB에는 SHA-256. 관리 작업은 관리자 세션 + CSRF가 필요하다.
- MCP는 세션 쿠키만으로 접근할 수 없다. 매 요청 Bearer 필수, HTTP 요청에서 추출한 소스 범위를 SDK 실행 문맥으로 전달한다.
- 일반 원본 폴더는 읽기 전용 마운트한다. 소스 밖 파일 링크·디렉터리 링크는 따라가지 않는다. 파일 이름 NFC, 크기/추출 문자 상한을 유지한다.
- 추출 형식은 TextExtractor, 선택 AI 기능은 Providers 인터페이스로 확장한다. 임베딩은 원격/ONNX 구현을 제공한다. 리랭커·OCR·ML 전용 서버 구현은 full 모듈에만 둔다. Qdrant는 core `VectorStore`/`QdrantVectorStore`(REST, 추가 의존성 없음)이며 기본은 Lucene HNSW다.
- 임베딩은 기본 off, 설정은 관리자 세션+CSRF, 키는 별도 로컬 키로 AES-GCM 암호화하고 API에서 비공개. 모델·차원이 바뀌면 전체 재색인 전 벡터 검색을 차단한다. 모델 파일은 이미지·저장소에 넣지 않는다. 자동 다운로드는 데이터 폴더 안에서만 허용한다. 내장 모델은 현재 512 MiB 목표를 초과하며 실측 제한을 반드시 명시한다.
- 메모리: 단일 색인 작업자, Lucene RAM 8 MiB, PDF 쪽 단위·디스크 scratch, 추출 상한, H2 연결 2개, Tomcat 스레드 16개. JDK 25 컴팩트 헤더·SerialGC 여유 힙 10/20%·즉시 수축, lite 힙 192 MiB/full 256 MiB. 실제 추출 뒤 작업자 유휴 5초에 GC를 한 번 요청하며 최소 5분 간격, 변경 없는 검사에는 요청하지 않는다. SerialGC에만 적용하고 `-Dnasquire.indexing.idle-gc=false`로 해제 가능하다.
- 긴 작업은 ROADMAP 체크리스트로 남기고 끝나기 전 빌드·테스트를 수행한다.
- `.ai-commit-msg`에 한국어 커밋 메시지를 남긴다. git add/commit/push는 하지 않는다.

- Office/HWP는 8 MiB 원본·ZIP 해제/노드·HWP 스트림·근거 단위 제한을 적용한다. XLSX 전체 워크북 로드 금지. 다운로드 기준 URL `nasquire.public-url`, 유효시간 `nasquire.download-ttl-seconds`(기본 300, 최대 3600초), 재시작 시 기존 주소 무효화. 수신 시 발급 토큰의 현재 유효성/소스 범위를 검사하고 NOFOLLOW+O_NONBLOCK/fstat으로 일반 파일을 다시 열며 서명/토큰 원문은 감사 로그에 기록하지 않는다.

- 업로드 설정: `nasquire.upload-max-bytes`(기본 32 MiB, 최대 1 GiB), `nasquire.trash-retention-days`(기본 30일). 멀티파트는 데이터 폴더 디스크에 저장하고 64 KiB 버퍼로 소스 내 임시 파일에 복사한 뒤 원자적으로 이동한다. 숨김 `.nasquire-*`는 색인/공유 제외. 쓰기는 관리자 세션+CSRF만 허용한다.
- Linux amd64/arm64에서만 네이티브 안전 폴더 생성/덮어쓰기 없는 이동을 제공한다. Java 25 FFM·libc `openat`/`mkdirat`/`renameat2`를 사용하며 `--enable-native-access=ALL-UNNAMED`가 필요하다. 미지원 환경은 안전하게 실패한다. 일반 소스 쓰기로 대체하지 않는다.
- `/s/**`는 관리 세션을 읽거나 생성하지 않는다. 공유 비밀번호는 HTTP Basic(사용자명 무시, HTTPS 필요), 실패 시 단계 지연·5회 실패 후 15분 제한. IP별 기본 분당 60요청·최대 4096항목(LRU), 전달 IP 헤더는 nasquire.trusted-proxies의 명시한 연결 IP에서만 사용한다. 비밀번호 잠금 키는 공유+IP다. 프록시는 공유 경로·Authorization·다운로드 쿼리를 로그에 기록하지 않도록 설정한다.
- 업로드·공유 실측/한계: `docs/verification/upload-share.md`. 업로드 폴더는 앱이 전용으로 관리하며 일반 소스는 계속 읽기 전용 마운트한다.

- 보안 검토 반영: multipart는 업로드 경로+관리 인증+헤더 CSRF 뒤에만 파싱한다. 거부는 오류 디스패치 없이 상태 코드로 반환한다. 공개 URL 미설정 시 상대 주소만 발급한다. 관리자 로그인은 IP/계정별 실패 지연·잠금·성공 초기화를 적용한다.
- 공유는 생성 시 fileKey를 저장하고 접근 때 대조한다(기존 식별자 없는 공유는 재생성 필요). 업로드 이동/휴지통/덮어쓰기는 대상과 하위 공유를 감사와 함께 철회한다. 폴더 깊이 최대 64, 휴지통 실패 행은 표시하고 1시간 뒤 재시도한다. 시작 시 24시간 지난 업로드 `.nasquire-*.part`와 multipart 파일만 정리한다. 일반 소스는 정리하지 않는다.
- 프록시 전달 헤더 전략은 기본 none이다. 선택 설정과 신뢰 경계는 `docs/verification/upload-share.md`의 보안 검토 반영 절을 따른다.

- 관리자 TOTP: 기본 off, `Totp` JDK HMAC-SHA1/6자리/30초/±1, 마지막 승인 단계 DB CAS·복구 코드 SHA-256 원자 소모. `TotpService`는 기존 `EmbeddingSettings`의 로컬 AES-GCM 키를 재사용한다. 등록/해제는 관리자+CSRF, 비밀번호 성공 후 TOTP 대기는 권한 없는 세션 속성(5분), 최종 승인 때 세션 ID·CSRF 교체. 등록 비밀·복구 코드는 최초 응답만 제공한다.
- 시도 제한은 `LoginAttempts.acquire` 표로 확인·진행 중 예약을 원자 처리하고 실패 횟수 리셋·허용 시각 단축을 금지한다. 등록/해제는 `totp-manage:` 키로 분리, 등록도 비밀번호 재확인. 암호문은 `v2:`+용도별 AAD(`totp`/`embedding`), 키가 없거나 바뀌었는데 암호문이 있으면 새 키를 만들지 않고 `KeyUnavailable`(503·복구 안내).
- 오프라인 복구: `java -jar app/build/libs/app-0.1.0.jar --recover-admin --data=/data` (앱 종료 필요). 웹 컨텍스트 없이 표준 입력 비밀번호를 검증하고 기존 DB 비밀번호 재설정·TOTP/복구 코드 삭제·감사를 트랜잭션 처리한다. 검증 기록은 `docs/verification/totp.md`.

- 관리 화면은 사이드바 하단 톱니바퀴의 중앙 환경설정 모달(모바일 전체 화면)에서 계정 → 2단계 인증 → 검색 → full 리랭커·OCR → 정보 → 로그아웃을 제공한다. 메뉴는 소스·파일/공유·API 토큰·검색만 유지하며 하단에는 자동/라이트/다크 세그먼트를 둔다. 정보는 버전·빌드·저작권·저장소·원본 라이선스 링크 및 라이선스 탭 모달을 유지한다. 검색에서 검색(임베딩·Qdrant·연결 시험·재색인)과 full 전용 리랭커·OCR(`frontend/src/Full.tsx`)을 제공한다. `/api/full.available`로 full 가용성을 판단하며 lite에서는 리랭커·OCR·내장 임베딩 선택지를 숨기고 정보 화면에만 빌드를 작게 표시한다. 검색과 full은 기존 별도 API·CSRF·검증 흐름으로 저장한다. 서버 가용성·키 비공개·주소 변경 시 키 폐기 정책을 따른다.
- full 설정은 `/api/full` GET/PUT(관리자 세션+헤더 CSRF). 기본 off, 리랭커 키는 기존 embedding.key를 공유하고 AES-GCM v2 + AAD `reranker`, full.properties 0600. full 암호문이 남으면 공유 키 재생성 금지. 키 복호화 실패·full 미포함 상태의 활성 설정은 시작 거부하며 키 백업 복원/기능 off 뒤 구성 전환이 필요하다.
- 리랭커는 일반 `/rerank` API만 구현, REST `rerank=true`·MCP `rerank: true` 선택. 소스 권한 적용 후 최대 topN만 전송하고 외부 순위만 수용하며 근거·원래 score 유지. 전체 시간/64 KiB/동시 외부 호출 1개 상한, 실패·혼잡 시 원래 순서.
- OCR은 full 전용 Tesseract CLI(`/usr/bin/prlimit`, `/usr/bin/tesseract`, kor+eng 데이터 사전 준비). 이미지 프레임/텍스트 없는 PDF 쪽만 처리, `location.type=ocr`·쪽 보존. 임시 파일은 데이터 폴더 ocr-tmp, 소스 쓰기 금지. 쪽당 시간/주소 공간/출력·파일당 쪽·픽셀/문자 제한. 과거 참고용 arm64 Linux VM PNG OCR·lite 컨테이너 실측은 `docs/verification/deploy.md`에 기록. 이미지 전용 PDF 실패는 FullSettings 위임의 원래 이름 누락으로 고정 fd를 image 모드로 처리한 것이 원인이며 이름 보존을 수정했다. 수정 후 arm64 full 컨테이너(1 GiB)에서 이미지 전용 PDF·PNG 한/영 OCR 검색을 확인했다. 실제 OCR 정확도 비교·full 메모리 보장은 미완료이며 이후 arm64 변경은 미검증(2026-10-26 공식 비지원 결정), `docs/verification/full.md` 참조.
- Qdrant: 설정은 `/api/embeddings`(`vectorStore`, `qdrantUrl`, `qdrantCollection`, `qdrantApiKey`, `qdrantTimeoutSeconds`), 키는 AAD `qdrant`로 `encryptedQdrantKey`에 저장·비공개. 검색은 소스 범위를 payload 필터로 보내고 응답을 앱에서 재검사한 뒤 Lucene `chunk` ID(범위 필터 포함)로만 본문을 얻는다. Qdrant에 본문을 보내지 않는다. 저장소 식별자 `vector.store`가 바뀌면 재색인 전 벡터 검색 차단.
- ML 전용 모드: `--nasquire.mode=ml`/`NASQUIRE_MODE=ml`이면 main에서 Spring 없이 full의 `MlServer`(JDK HTTP)를 띄운다. `/v1/embeddings`·`/v1/rerank`·`/v1/ocr`·`/v1/models`만, 모든 요청 Bearer(SHA-256 상수 시간 비교). 토큰은 `nasquire.ml.token`(설정 파일·환경변수)·`nasquire.ml.token-file`만, 명령행 토큰 거부. Spring 컨텍스트의 `nasquire.mode`는 `search`만 허용한다. 실측: `VERIFY_PORT=8102 python3 scripts/verify-ml.py`(full bootJar를 다시 만든다).
- 보안 검토 반영(`docs/verification/full.md`): 원격 임베딩·Qdrant·원격 리랭커의 비루프백 평문 http는 `allowInsecureHttp=true`일 때만(화면 경고), 기존 설정은 시작하되 해당 기능만 끄고 `disabledReason` 표시. 주소 변경 시 저장 키 폐기, 키 삭제는 `DELETE /api/embeddings/key|qdrant-key`, `/api/full/key`. SearchIndex는 원격 호출을 잠금 밖에서 하고 세대 번호로 검증한다(잠금 안에 원격 호출 추가 금지). close()는 신규 작업 차단→활성 작업 대기(초과 시 중단)→닫기, 진행 중 replace는 같은 key·source 삭제 시 게시하지 않고 Qdrant 점을 지운다. Qdrant 점에는 영속 `vector.epoch`를 두고 현재 세대만 검색한다. 시작 시 `ocr-tmp`의 오래된 `page-*`/`job-*`를 OCR 설정과 무관하게 정리한다. Qdrant 작업 전체 상한·연속 3회 실패 30초 차단. 주기 색인은 Throwable을 잡아 계속한다. OCR 원본 열기·검사·렌더링/디코딩은 자식 JVM(`OcrSandbox`/`OcrWorker`, 앱 main 첫 인자 `--nasquire.mode=ocr-worker`)에서 prlimit·작은 힙·환경 초기화·`ocr-tmp/job-*`(0700)·파일 단위 시간 상한(자손까지 강제 종료)·동시 1개로 처리한다. 같은 JVM 렌더 스레드를 다시 두지 않는다. 6M 픽셀·부분 샘플링. ML 서버는 기본 127.0.0.1, 대기열 없는 작업자·포화 503, 외부 노출은 TLS 리버스 프록시.
- 로컬 리랭커: cross-encoder/mmarco-mMiniLMv2-L12-H384-v1 고정 커밋 qint8·SHA-256, 데이터 폴더 안 다운로드만. 512 MiB 목표 초과(실측은 `docs/verification/full.md`).
- 최종 재확인: 원본 읽기·다운로드·업로드 네이티브 작업은 core `SecureFiles`로 공통화한다. 절대 경로의 모든 디렉터리를 openat·NOFOLLOW로 고정하고 마지막 파일은 O_NOFOLLOW|O_NONBLOCK+열린 fd의 fstat으로 일반 파일인지 확인한다. 색인기는 텍스트·Office·OCR 모두 고정 fd 경로로 읽으며 형식 판별용 원래 이름은 별도로 전달한다. 미지원 환경에서 안전하지 않은 경로 읽기로 폴백하지 않는다.
- OCR 복사는 자식 실행과 같은 시간 상한을 쓰며 setsid 프로세스 그룹·자손 종료 확인 뒤 결과를 검사하고 슬롯을 반환한다(종료 확인 지연 시 회수자가 슬롯 유지). 부모도 PNG 쪽당 16 MiB·합계 256 MiB와 열린 fd 일반 파일을 재검사한다. ocr-worker 직접 실행은 실경로 `ocr-tmp/job-*`·현재 사용자 소유·0700 폴더만 허용한다. SearchIndex의 두 번째 종료 대기 뒤에도 활성 작업이 있으면 공급자·저장소 폐기는 마지막 exit()까지 미루며 실제 close는 공통 잠금 밖에서 한다.

- 공유 전송은 열린 fd의 fstat dev/ino를 저장된 fileKey와 대조하고 그 fd만 사용한다. 폴더 공유는 확인한 루트 핸들을 유지하며 core SecureFiles의 openat·NOFOLLOW로 하위를 연다. OCR 회수의 `/proc` 열거·stat 읽기/파싱 실패는 종료 미확인으로 처리해 슬롯·작업 폴더를 보유한다. NAS/NFS 일반 파일 I/O 자체가 반환하지 않으면 복사·추출 시간 상한은 보장하지 못한다(일반 텍스트와 동일).

- MCP OAuth: `nasquire.oauth.enabled` 기본 off, HTTPS 공개 origin 필요(루프백 HTTP 개발 예외). JDBC 내장 인가 서버·DCR 공개 클라이언트·S256 PKCE·resource/audience 고정, 외부 토큰 전달 금지.
- OAuth 코드는 60초·1회용, 액세스는 1시간·MCP 전용, 리프레시는 회전·30일 상한·직전 토큰 10초 동일 응답 유예(다음 회전 전)·그 밖의 재사용 시 계열 철회. 원문 저장/로그 금지, 관리자 승인·관리 철회는 기존 세션(TOTP 포함)+CSRF.
- OAuth 소스는 명시 승인 집합만 허용하며 빈 집합은 권한 없음이다. `oauth:` 식별자의 현재 권한 재검사를 다운로드에도 적용한다. 검증은 `VERIFY_PORT=8104 python3 scripts/verify-oauth.py`, 상세는 `docs/verification/oauth.md`.

- OAuth 승인은 최근 10분 내 관리자 인증이 필요하며 pending/nonce는 단일 세션 객체다. 토큰/철회 쿼리는 거부하고, IPv6 /64 제한·정상 토큰 증명 예외·미승인 IP당 20개·감사 종류별 분당 1회·마지막 승인 만료 후 30일 정리를 적용한다. 프록시 설정/신뢰 경계는 `docs/install/oauth.md`를 따른다.

- 추출 실패 WARN은 소스 상대 경로·예외 종류만 기록하며 원문 예외 메시지/스택은 출력하지 않는다. OCR은 `ExtractionFailure`의 안전한 단계·종료 코드·시간 초과·예외 종류를 전달한다. 관리자 전용 소스 상태 `recentFailures`는 현재 검사 최근 최대 5건(메모리 전용)이다.

- 검색 결과는 소스 이름·상대 경로로 표시하고 내부 path는 문서 API 호출에 유지한다. `SearchExcerpt.tsx`는 입력 질의 문자·숫자 토큰의 대소문자 무시 강조(React 텍스트 노드), 공백 축약·360자 주변 발췌·전체 청크 펼치기를 담당한다. REST·MCP 응답 형식은 바꾸지 않는다.

- 계정 변경은 `/api/account` GET, `/api/account/password|username` PUT(관리자 세션+헤더 CSRF). 현재 비밀번호·활성 TOTP의 미사용 인증 앱 코드·`account-change:` 제한을 적용한다. 성공 시 DB `administrator.generation`을 CAS 증가시키고 현재 세션 ID·CSRF를 교체한다. 다른 관리자/코드 대기 세션은 다음 요청에 무효화한다. 사용자명은 최초 설정과 같은 ASCII 3~100자 규칙이다. 감사에는 변경 값을 기록하지 않는다.
- 토큰 행 삭제는 `DELETE /api/tokens/{id}/record`, 일괄은 `/api/tokens/inactive`. 활성 토큰 삭제 409, 감사 보존·삭제 행위 기록, 다운로드 티켓은 삭제 후에도 현재 토큰 조회 실패로 거부한다.

- 색인 기본 주기 300000ms·stable 2000ms. `IndexingSettings`의 데이터 폴더 `indexing.properties`가 환경 초기값보다 우선한다. `/api/indexing` GET/PUT은 관리자+헤더 CSRF·감사, 화면 범위 주기 1~60분·파일 1~512 MiB. 저장 즉시 같은 단일 작업자 스케줄 재설정. 소스 `/scan`은 변경분 검사 요청을 다음 한 번으로 합친다. 크기 초과는 오류와 분리하고 최근 상대 경로 5개를 관리자 상태에 메모리 보관한다. `/api/supported-extensions`는 빌드 기준(lite 문서, full 이미지 추가)이며 OCR off에서도 full 형식 안내는 유지한다. Office/HWP 8 MiB 별도 상한 유지.

- 관리 화면 스크롤바는 `frontend/src/scrollbars.ts`가 호버·포커스 영역에 6px 오버레이를 표시한다. CSS에서 기본 스크롤바 공간을 제거하되 기본 휠·터치·키보드 스크롤을 유지하고, 강제 색 모드에서는 기본 스크롤바를 사용한다. 폭 전환·scrollbar-gutter로 레이아웃을 흔들지 않는다.

- 안정화 대기 파일은 같은 단일 작업자가 소스별 후속 검사를 예약한다(최단 안정화 시각+25ms, 중복 병합, 연속 최대 10회 후 정기 검사로 복귀). 시작·주기 변경·소스 저장·재색인·OCR 재색인은 즉시 검사한다. 동일 소스 재색인은 기존 결과를 파일별 replace까지 유지하며 삭제 파일은 정리한다. 시작/소스 범위 변경/전체 벡터 재구축은 범위·세대 안전을 위해 기존 정리 경로를 유지한다. 파일 화면에 업로드 소스가 없으면 한/영 마운트·등록 안내와 소스 관리 이동 버튼을 표시한다.
