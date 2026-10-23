# Nasquire 로드맵
> 공개 문서. 내부 환경 정보는 넣지 않는다.

## 목표
- 기존 폴더(NAS 공유·로컬)를 읽기 전용으로 감시 → 텍스트 추출 → 키워드(BM25, Nori)+벡터 혼합 색인 → 검색 API·MCP 도구로 제공.
- MCP 도구: `search_documents`(조각+경로+쪽), `get_document`(범위 텍스트), `download_file`(짧은 유효 주소). 기본 읽기 전용.
- 근거 위치: PDF 쪽, PPT 슬라이드, Excel 시트·행, Word 제목·문단.
- 로그인: 첫 실행 때 관리자 생성(비밀번호+TOTP 선택). 클라이언트별 API 토큰(한 번만 표시·해시 저장·만료·소스 범위·읽기/쓰기·감사 로그).

## 단계
- [ ] 1단계: 골격과 키워드 검색 MVP — 멀티 모듈, 소스 등록, PDF·텍스트·마크다운 추출, Lucene Nori 색인·검색, REST·MCP(search_documents·get_document), 관리자·토큰, 관리 화면 기본, Dockerfile·compose, 메모리 512MB 실측.
- [ ] 2단계: 벡터 검색 — 임베딩 공급자(내장 ONNX 소형 다국어 int8 / OpenAI 호환 원격), 혼합 검색, 색인에 모델 ID 기록.
- [ ] 3단계: 형식 확장 — Word·PPT·Excel(POI, 스트리밍), HWP·HWPX, download_file 짧은 주소.
- [ ] 4단계: full 모듈 — OCR(PaddleOCR ONNX vs Tesseract 실측), 리랭커, Qdrant, ML 전용 모드.
- [ ] 5단계: 배포 — amd64·arm64 이미지 `:lite`·`:full`, CI, NAS 설치 안내(Synology·Unraid·TrueNAS), MCP OAuth.

## 결정
- Spring Boot + React, Gradle 멀티 모듈. 자바 유지 여부는 1단계 RSS 실측으로 판단.
- 라이선스 AGPL-3.0 + 비상업 추가 허가·출처 표시(2026-10-24).
- 관리 화면 디자인은 Bluesounds 공통 디자인(어두운 남색 계열, 시그니처 아이콘).
