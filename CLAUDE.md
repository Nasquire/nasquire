# Nasquire 지침
> 공개 저장소다. 코드·문서·커밋에 내부 주소·도메인·서버 이름·계정·비밀값·사내 경로를 넣지 않는다. 예시는 `example.com`, `/data`, `/sources/...`처럼 일반 값만 쓴다.

## 개요
- 기존 폴더를 읽기 전용으로 색인하는 단일 컨테이너 검색 서비스("Ask your NAS").
- 원본에는 절대 쓰지 않는다. Lucene·H2는 별도 데이터 폴더에 둔다. 소스와 데이터 폴더 중첩 금지.
- Java 25 toolchain, Gradle 9.8.1, Spring Boot 4.1.2. 최저 목표는 512 MiB 컨테이너.

## 구조
- `core/src/main/java/com/bluesounds/nasquire/core`: Source·glob, 추출기(PDFBox·텍스트/ICU·POI 이벤트 Excel/Office·hwplib/hwpxlib), 단일 저우선순위 주기 검사, Lucene Nori/BM25, 선택 공급자 인터페이스·원격/ONNX 임베딩·HNSW/RRF.
- `app/src/main/java/com/bluesounds/nasquire/app`: Spring MVC REST, H2/JDBC, BCrypt 관리자 세션·CSRF, 해시 API 토큰, 감사 로그, 공식 MCP SDK의 stateless Streamable HTTP(`/mcp`), HMAC 짧은 다운로드 주소(`/api/download`, `/download`).
- `frontend/src`: React·Vite·TypeScript 관리 화면(브라우저 한/영, 자동/라이트/다크). Gradle이 Node를 내려받고 npm ci로 빌드하여 app의 static에 포함한다.
- `frontend/public/icon.svg`: 시그니처 아이콘 복사본. 원본 자산은 수정하지 않는다.
- `deploy/docker-compose.yml`, `Dockerfile`: 비루트 JRE 런타임, PUID/PGID 빌드 인자와 사용자 매핑, 읽기 전용 소스, 데이터 볼륨.
- `scripts/smoke.py`: 새 서버 데이터에 대한 curl 실제 API 검증(인증값 미출력).
- `scripts/verify-runtime.py`: 문서 생성·bootRun·curl·RSS·서버 종료를 자동 수행. 결과는 무시되는 `build/verification/`에 생성한다.
- `scripts/fetch-verification-models.py`: 명시적 내장 모델 검증 시 고정 리비전·SHA-256 모델 파일을 무시되는 build 폴더에 준비한다.
- `docs/embeddings.md`, `docs/verification/vector.md`: 공급자·재색인·모델 출처·다중 아키텍처·실측 제한.
- `docs/ROADMAP.md`, `docs/verification/mvp.md`, `THIRD-PARTY.md`: 진행·실측·의존성 라이선스.

## 명령
- 빌드·테스트: `./gradlew build -q`
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
- 원본 폴더는 읽기 전용 마운트한다. 소스 밖 파일 링크·디렉터리 링크는 따라가지 않는다. 파일 이름 NFC, 크기/추출 문자 상한을 유지한다.
- 추출 형식은 TextExtractor, 선택 AI 기능은 Providers 인터페이스로 확장한다. 임베딩은 원격/ONNX 구현을 제공한다. 리랭커·OCR 구현은 후속 단계다.
- 임베딩은 기본 off, 설정은 관리자 세션+CSRF, 키는 별도 로컬 키로 AES-GCM 암호화하고 API에서 비공개. 모델·차원이 바뀌면 전체 재색인 전 벡터 검색을 차단한다. 모델 파일은 이미지·저장소에 넣지 않는다. 자동 다운로드는 데이터 폴더 안에서만 허용한다. 내장 모델은 현재 512 MiB 목표를 초과하며 실측 제한을 반드시 명시한다.
- 메모리: 단일 색인 작업자, Lucene RAM 8 MiB, PDF 쪽 단위·디스크 scratch, 추출 상한, H2 연결 2개, Tomcat 스레드 16개.
- 긴 작업은 ROADMAP 체크리스트로 남기고 끝나기 전 빌드·테스트를 수행한다.
- `.ai-commit-msg`에 한국어 커밋 메시지를 남긴다. git add/commit/push는 하지 않는다.

- Office/HWP는 8 MiB 원본·ZIP 해제/노드·HWP 스트림·근거 단위 제한을 적용한다. XLSX 전체 워크북 로드 금지. 다운로드 기준 URL `nasquire.public-url`, 유효시간 `nasquire.download-ttl-seconds`(기본 300), 재시작 시 기존 주소 무효화. 수신 시 SecureDirectoryStream+NOFOLLOW로 다시 열며 서명/토큰 원문은 감사 로그에 기록하지 않는다.
