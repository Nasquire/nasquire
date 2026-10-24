# Nasquire 지침
> 공개 저장소다. 코드·문서·커밋에 내부 주소·도메인·서버 이름·계정·비밀값·사내 경로를 넣지 않는다. 예시는 `example.com`, `/data`, `/sources/...`처럼 일반 값만 쓴다.

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
- `deploy/docker-compose.yml`, `Dockerfile`: 비루트 JRE 런타임, PUID/PGID 빌드 인자와 사용자 매핑, 읽기 전용 소스, 데이터 볼륨.
- `scripts/smoke.py`: 새 서버 데이터에 대한 curl 실제 API 검증(인증값 미출력).
- `scripts/verify-runtime.py`: 문서 생성·bootRun·curl·RSS·서버 종료를 자동 수행. 결과는 무시되는 `build/verification/`에 생성한다.
- `scripts/fetch-verification-models.py`: 명시적 내장 모델 검증 시 고정 리비전·SHA-256 모델 파일을 무시되는 build 폴더에 준비한다.
- `docs/embeddings.md`, `docs/verification/vector.md`: 공급자·재색인·모델 출처·다중 아키텍처·실측 제한.
- `docs/ROADMAP.md`, `docs/verification/mvp.md`, `THIRD-PARTY.md`: 진행·실측·의존성 라이선스.

## 명령
- 빌드·테스트(기본 lite): `./gradlew build -q` (`:app:verifyLiteClasspath`가 full/ONNX/DJL 유입 검사)
- full 선택 빌드·실행: `./gradlew -Pfull build -q`, `./gradlew -Pfull :app:bootRun`; 공급자 테스트 `./gradlew :full:test -q`. 앱 JAR 경로는 같으므로 마지막 빌드 구성에 유의한다. 기존 Dockerfile은 lite다.
- 코어/통합 테스트: `./gradlew :core:test :app:test -q`
- 실행: `./gradlew :app:bootRun` (포트 8080, 저장소 루트 `./data`)
- 별도 설정: `./gradlew :app:bootRun --args='--server.port=8101 --nasquire.data=./data'`
- 자체 작성 문서 48개 생성(PDF·Markdown 40개와 Office/HWP 8개): `./gradlew :core:generateSamples -q` (`build/samples`)
- Linux 실측: `VERIFY_PORT=8101 python3 scripts/verify-runtime.py` (Python 3·curl 필요, 새 임시 DB, 종료 자동 처리)
- 벡터 실측: `VERIFY_PORT=8101 VERIFY_EMBEDDING=builtin python3 scripts/verify-runtime.py` (모델 다운로드·해시 검사 포함). `remote`는 가짜 HTTP 공급자 검증. 빌드와 bootRun 실측은 클래스 출력 폴더를 공유하므로 동시에 실행하지 않는다.
- 컨테이너: `SOURCE_DIR=/path/to/documents docker compose -f deploy/docker-compose.yml up --build -d`

## 구현 규칙
- AGPL 호환 의존성만 사용한다. 라이선스 변경 시 `THIRD-PARTY.md`를 갱신한다. LICENSE·LICENSE-EXCEPTIONS.md·NOTICE는 임의 변경하지 않는다.
- 저작권 `Copyright (c) 2026 Bluesounds`, 정보 화면 저장소·라이선스 링크를 유지한다.
- 계정·토큰 원문을 로그·DB에 남기지 않는다. 토큰은 읽기 전용, DB에는 SHA-256. 관리 작업은 관리자 세션 + CSRF가 필요하다.
- MCP는 세션 쿠키만으로 접근할 수 없다. 매 요청 Bearer 필수, HTTP 요청에서 추출한 소스 범위를 SDK 실행 문맥으로 전달한다.
- 일반 원본 폴더는 읽기 전용 마운트한다. 소스 밖 파일 링크·디렉터리 링크는 따라가지 않는다. 파일 이름 NFC, 크기/추출 문자 상한을 유지한다.
- 추출 형식은 TextExtractor, 선택 AI 기능은 Providers 인터페이스로 확장한다. 임베딩은 원격/ONNX 구현을 제공한다. 리랭커·OCR 구현은 full 모듈에만 둔다. Qdrant·ML 전용 모드는 아직 미구현이다.
- 임베딩은 기본 off, 설정은 관리자 세션+CSRF, 키는 별도 로컬 키로 AES-GCM 암호화하고 API에서 비공개. 모델·차원이 바뀌면 전체 재색인 전 벡터 검색을 차단한다. 모델 파일은 이미지·저장소에 넣지 않는다. 자동 다운로드는 데이터 폴더 안에서만 허용한다. 내장 모델은 현재 512 MiB 목표를 초과하며 실측 제한을 반드시 명시한다.
- 메모리: 단일 색인 작업자, Lucene RAM 8 MiB, PDF 쪽 단위·디스크 scratch, 추출 상한, H2 연결 2개, Tomcat 스레드 16개.
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

- full 설정은 `/api/full` GET/PUT(관리자 세션+헤더 CSRF). 기본 off, 리랭커 키는 기존 embedding.key를 공유하고 AES-GCM v2 + AAD `reranker`, full.properties 0600. full 암호문이 남으면 공유 키 재생성 금지. 키 복호화 실패·full 미포함 상태의 활성 설정은 시작 거부하며 키 백업 복원/기능 off 뒤 구성 전환이 필요하다.
- 리랭커는 일반 `/rerank` API만 구현, REST `rerank=true`·MCP `rerank: true` 선택. 소스 권한 적용 후 최대 topN만 전송하고 외부 순위만 수용하며 근거·원래 score 유지. 전체 시간/64 KiB/동시 외부 호출 1개 상한, 실패·혼잡 시 원래 순서.
- OCR은 full 전용 Tesseract CLI(`/usr/bin/prlimit`, `/usr/bin/tesseract`, kor+eng 데이터 사전 준비). 이미지 프레임/텍스트 없는 PDF 쪽만 처리, `location.type=ocr`·쪽 보존. 임시 파일은 데이터 폴더 ocr-tmp, 소스 쓰기 금지. 쪽당 시간/주소 공간/출력·파일당 쪽·픽셀/문자 제한. 실제 OCR 비교·full 메모리 보장은 미완료, `docs/verification/full.md` 참조.
- `nasquire.mode`는 현재 `search`만 지원. 미구현 `ml`을 주면 시작 거부한다. 일반 검색 서버를 ML 전용 서버로 안내하지 않는다.
