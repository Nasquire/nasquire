# Nasquire 지침
> 공개 저장소다. 코드·문서·커밋에 내부 주소·도메인·서버 이름·계정·비밀값·사내 경로를 넣지 않는다. 예시는 `example.com`, `/data`, `/sources/...`처럼 일반 값만 쓴다.

## 개요
- 기존 폴더를 읽기 전용으로 색인하는 단일 컨테이너 검색 서비스("Ask your NAS").
- 원본에는 절대 쓰지 않는다. Lucene·H2는 별도 데이터 폴더에 둔다. 소스와 데이터 폴더 중첩 금지.
- Java 25 toolchain, Gradle 9.8.1, Spring Boot 4.1.2. 최저 목표는 512 MiB 컨테이너.

## 구조
- `core/src/main/java/com/bluesounds/nasquire/core`: Source·glob, 추출기(PDFBox·텍스트/ICU), 단일 저우선순위 주기 검사, Lucene Nori/BM25, 선택 공급자 인터페이스.
- `app/src/main/java/com/bluesounds/nasquire/app`: Spring MVC REST, H2/JDBC, BCrypt 관리자 세션·CSRF, 해시 API 토큰, 감사 로그, 공식 MCP SDK의 stateless Streamable HTTP(`/mcp`).
- `frontend/src`: React·Vite·TypeScript 관리 화면(브라우저 한/영, 자동/라이트/다크). Gradle이 Node를 내려받고 npm ci로 빌드하여 app의 static에 포함한다.
- `frontend/public/icon.svg`: 시그니처 아이콘 복사본. 원본 자산은 수정하지 않는다.
- `deploy/docker-compose.yml`, `Dockerfile`: 비루트 JRE 런타임, PUID/PGID 빌드 인자와 사용자 매핑, 읽기 전용 소스, 데이터 볼륨.
- `scripts/smoke.py`: 새 서버 데이터에 대한 curl 실제 API 검증(인증값 미출력).
- `scripts/verify-runtime.py`: 문서 생성·bootRun·curl·RSS·서버 종료를 자동 수행. 결과는 무시되는 `build/verification/`에 생성한다.
- `docs/ROADMAP.md`, `docs/verification/mvp.md`, `THIRD-PARTY.md`: 진행·실측·의존성 라이선스.

## 명령
- 빌드·테스트: `./gradlew build -q`
- 코어/통합 테스트: `./gradlew :core:test :app:test -q`
- 실행: `./gradlew :app:bootRun` (포트 8080, 저장소 루트 `./data`)
- 별도 설정: `./gradlew :app:bootRun --args='--server.port=8101 --nasquire.data=./data'`
- 자체 작성 문서 40개 생성: `./gradlew :core:generateSamples -q` (`build/samples`)
- Linux 실측: `VERIFY_PORT=8101 python3 scripts/verify-runtime.py` (Python 3·curl 필요, 새 임시 DB, 종료 자동 처리)
- 컨테이너: `SOURCE_DIR=/path/to/documents docker compose -f deploy/docker-compose.yml up --build -d`

## 구현 규칙
- AGPL 호환 의존성만 사용한다. 라이선스 변경 시 `THIRD-PARTY.md`를 갱신한다. LICENSE·LICENSE-EXCEPTIONS.md·NOTICE는 임의 변경하지 않는다.
- 저작권 `Copyright (c) 2026 Bluesounds`, 정보 화면 저장소·라이선스 링크를 유지한다.
- 계정·토큰 원문을 로그·DB에 남기지 않는다. 토큰은 읽기 전용, DB에는 SHA-256. 관리 작업은 관리자 세션 + CSRF가 필요하다.
- MCP는 세션 쿠키만으로 접근할 수 없다. 매 요청 Bearer 필수, HTTP 요청에서 추출한 소스 범위를 SDK 실행 문맥으로 전달한다.
- 원본 폴더는 읽기 전용 마운트한다. 소스 밖 파일 링크·디렉터리 링크는 따라가지 않는다. 파일 이름 NFC, 크기/추출 문자 상한을 유지한다.
- 추출 형식은 TextExtractor, 선택 AI 기능은 Providers 인터페이스로 확장한다. 1단계에서 임베딩·리랭커·OCR 구현은 하지 않는다.
- 메모리: 단일 색인 작업자, Lucene RAM 8 MiB, PDF 쪽 단위·디스크 scratch, 추출 상한, H2 연결 2개, Tomcat 스레드 16개.
- 긴 작업은 ROADMAP 체크리스트로 남기고 끝나기 전 빌드·테스트를 수행한다.
- `.ai-commit-msg`에 한국어 커밋 메시지를 남긴다. git add/commit/push는 하지 않는다.
