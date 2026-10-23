# Nasquire 지침
> 공개 저장소다. 이 파일·문서·코드·커밋에 내부 주소·도메인·서버 이름·계정·비밀값·사내 경로를 넣지 않는다. 예시는 `example.com`, `/data`, `/sources/...`처럼 일반 값만 쓴다.

## 개요
- NAS·서버의 기존 폴더를 읽기 전용으로 색인해 검색 API와 MCP 서버로 제공하는 단일 컨테이너 서비스("Ask your NAS").
- 원본에는 절대 쓰지 않는다. 색인·설정·DB는 별도 데이터 폴더(`/data`).
- 최저 사양 목표: arm64 4코어·램 1GB NAS에서 컨테이너 메모리 512MB 이내(lite).

## 구조 (Gradle 멀티 모듈)
- `core` 추출·색인·검색(Lucene, 한국어 Nori)·공급자 인터페이스(임베딩·리랭커·OCR 내장/원격).
- `app` Spring Boot 실행 모듈: REST API, MCP(Streamable HTTP), 로그인·토큰, 관리 화면 정적 파일 포함.
- `frontend` React·Vite·TypeScript 관리 화면. Gradle이 Node를 받아 빌드하고 app jar의 static에 넣는다.
- 선택 모듈(ocr, rerank, qdrant 등)은 이후 단계. 있으면 켜지는 방식(ServiceLoader/조건부 빈).
- 배포판: `lite`(core만)·`full`(전부), amd64·arm64.

## 명령
- 빌드·테스트: `./gradlew build` (조용히: `./gradlew build -q`)
- 실행: `./gradlew :app:bootRun` (기본 포트 8080, 데이터 폴더 `./data`)
- 컨테이너: `docker compose up` (`deploy/docker-compose.yml`)

## 규칙
- 라이선스 AGPL-3.0 + `LICENSE-EXCEPTIONS.md`. 새 의존성은 Apache-2.0·MIT·BSD 등 호환 라이선스만. AGPL/비상업 모델 라이선스 의존 금지(PyMuPDF·MinIO 등).
- 저작권 표시는 `Copyright (c) 2026 Bluesounds`. 관리 화면 정보(About)에 저장소 링크 유지.
- 모든 외부 주소(임베딩·LLM·벡터 DB)·경로는 설정값. 하드코딩 금지.
- 메모리 예산을 의식한다: 스트리밍 읽기, 파일 하나씩 낮은 우선순위 색인, 큰 라이브러리 전체 묶음(Tika 전체 등) 대신 필요한 파서만.
- 커밋 메시지 한국어. 작업 계획·진행은 `docs/ROADMAP.md`.
