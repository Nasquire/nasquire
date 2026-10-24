# Nasquire 로드맵
> 공개 문서. 내부 환경 정보는 넣지 않는다.

## 목표
- 기존 폴더(NAS 공유·로컬)를 읽기 전용으로 감시 → 텍스트 추출 → 키워드(BM25, Nori)+벡터 혼합 색인 → 검색 API·MCP 도구로 제공.
- MCP 도구: `search_documents`(조각+경로+쪽), `get_document`(범위 텍스트), `download_file`(짧은 유효 주소). 기본 읽기 전용.
- 근거 위치: PDF 쪽, PPT 슬라이드, Excel 시트·행, Word 제목·문단.
- 로그인: 첫 실행 때 관리자 생성(비밀번호+TOTP 선택). 클라이언트별 API 토큰(한 번만 표시·해시 저장·만료·소스 범위·읽기/쓰기·감사 로그).

## 단계
- [x] 1단계: 골격과 키워드 검색 MVP — 멀티 모듈, 소스 등록, PDF·텍스트·마크다운 추출, Lucene Nori 색인·검색, REST·MCP(search_documents·get_document), 관리자·토큰, 관리 화면 기본, Dockerfile·compose, 메모리 512MB 실측.
- [ ] 2단계: 벡터 검색 — 임베딩 공급자(내장 ONNX 소형 다국어 int8 / OpenAI 호환 원격), 혼합 검색, 색인에 모델 ID 기록.
- [ ] 3단계: 형식 확장 — Word·PPT·Excel(POI, 스트리밍), HWP·HWPX, download_file 짧은 주소.
- [ ] 3.5단계: 업로드·공유 — 선택 기능 "업로드 소스"(기본 끔, 별도 쓰기 가능 폴더만 쓰고 일반 소스는 계속 읽기 전용, 웹 업로드·폴더·휴지통), 외부 공유 링크(파일 또는 폴더 하나, 무작위 토큰 `/s/{token}`, 만료·비밀번호·다운로드 횟수 제한·철회·감사 로그, 실경로 범위 검사·심볼릭 링크 탈출 차단·속도 제한, 범위 밖 목록·검색·MCP 노출 없음).
- [ ] 3.6단계: 임베드용 core 라이브러리 — 앱 의존 없는 공개 API 정리, Maven 패키지 배포(`nasquire-core`), 버전 정책(SemVer), 사용 예제. 확장 지점(인증·저장 위치·원본 열기 주소)을 설정·인터페이스로.
- [ ] 4단계: full 모듈 — OCR(PaddleOCR ONNX vs Tesseract 실측), 리랭커, Qdrant, ML 전용 모드.
- [ ] 5단계: 배포 — amd64·arm64 이미지 `:lite`·`:full`, CI, NAS 설치 안내(Synology·Unraid·TrueNAS), MCP OAuth.

## 결정
- Spring Boot + React, Gradle 멀티 모듈. 자바 유지 여부는 1단계 RSS 실측으로 판단.
- 라이선스 AGPL-3.0 + 비상업 추가 허가·출처 표시(2026-10-24).
- 관리 화면 디자인은 Bluesounds 공통 디자인(어두운 남색 계열, 시그니처 아이콘).
- 업로드·공유 링크는 별도 앱이 아니라 Nasquire 관리 화면에 둔다(관리 화면 하나, 2026-10-24). 원본 소스 읽기 전용 원칙은 유지하고 쓰기는 업로드 소스에만.
- core는 다른 앱에 넣어 쓸 수 있게 독립 라이브러리로 배포한다(2026-10-24). 외부 기여를 받기 전에 기여 정책(CLA/DCO)을 정한다.


## 1단계 구현·검증 (2026-10-24)

- [x] Gradle 9.8.1 wrapper·Java 25 toolchain·core/app/frontend 멀티 모듈.
- [x] Spring Boot 4.1.2(안정 릴리스), React·Vite·TypeScript, Gradle Node 다운로드와 npm ci.
- [x] 소스 CRUD·포함/제외 glob·기본 임시/NAS 파일 제외·NFC 경로·파일 크기 상한·소스/데이터 중첩 차단.
- [x] 원본 읽기 전용, 소스 밖 파일 링크 및 디렉터리 링크 제외, 단일 낮은 우선순위 주기 검사, mtime/크기 안정화·변경/삭제·재색인·오류 상태.
- [x] PDFBox 3.0.8 PDF 쪽 추출, UTF-8 우선/ICU 인코딩 추정 텍스트·마크다운, 추출 문자 상한.
- [x] Lucene 10.5.2 Nori·영문/BM25·1200자 조각·경로/쪽/수정시각·소스 필터·범위 텍스트.
- [x] H2 파일 DB, 최초 관리자 생성·BCrypt(비용 12), 세션·CSRF·로그아웃.
- [x] 읽기 전용 API 토큰(256비트 난수·1회 표시·SHA-256만 저장·이름/만료/소스 범위·폐기), 제한된 감사 로그.
- [x] REST 검색/문서·소스 상태/재색인·토큰 관리, MCP search_documents/get_document.
- [x] 관리 화면 6개(첫 설정·로그인·소스·토큰·검색·정보), 한/영, 공통 색·글꼴·간격·테마·메뉴, 시그니처 SVG 복사.
- [x] 다단계 JRE/비루트 Dockerfile, PUID/PGID 빌드 인자·사용자 매핑, :ro 소스·/data 볼륨·512m compose 예시.
- [x] core 테스트 3개, 실제 HTTP 통합 테스트 1개: 한국어/영문·PDF 쪽·인코딩·변경/삭제·링크·범위·CSRF·토큰 제한/만료/폐기·MCP.
- [x] `./gradlew build -q` 통과. `:app:bootRun` + curl로 최초 설정 → 로그인 → 40문서 색인 → 검색 → 토큰 → MCP 두 도구·감사·폐기·로그아웃 확인.
- [x] 임베딩·리랭커·OCR 공급자 인터페이스만 정의(구현 없음).

### 판단한 것

- MCP는 **공식 Java SDK 2.1.0의 Servlet stateless Streamable HTTP**를 선택했다. Spring AI 전체 모델/오케스트레이션 계층이 불필요한 두 읽기 도구라 직접 SDK가 의존성과 설정을 줄인다. 서버는 매 HTTP 요청의 인증 문맥을 도구 호출에 전달하고 항상 소스 범위를 재검사한다. 세션 ID/상주 SSE 연결 없이 POST JSON 응답을 사용한다. `/mcp`의 관리자 세션 단독 접근은 금지하며 Origin 헤더는 명시한 허용 목록만 받는다.
- H2는 내장 파일 DB로 사용하며 MPL-2.0 옵션을 선택했다. ICU는 Unicode-3.0, 나머지 주요 런타임은 Apache-2.0/MIT이다. Jakarta Annotations의 GPL + Classpath Exception 옵션을 포함하여 `THIRD-PARTY.md`에 정리했다. Logback 대신 Apache Log4j2를 사용했다. 루트 LICENSE·LICENSE-EXCEPTIONS.md·NOTICE는 변경하지 않았다.
- 1단계에서는 TOTP를 생략했다. 미구현 기능/배포 검증은 아래 후속 목록에 남긴다.
- 재시작 시 소스별 색인을 재구축한다. mtime/크기를 모두 보존하는 변경은 재색인 버튼으로 반영한다. 자동 색인 상태는 파일 수이며 검색 결과 수는 조각 수다.

### 512 MiB 근사 실측

- 자체 작성 Markdown 20개(한국어/영문, 각 60문단), 2쪽 PDF 20개(영문), 합계 40파일/40 PDF쪽.
- JVM: `-Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:MaxDirectMemorySize=32m`.
- Lucene RAM buffer 8 MiB, H2 연결 최대 2, Tomcat 최대 16스레드. PDF 텍스트는 쪽마다 처리하고 PDFBox scratch는 디스크를 사용한다.
- 최종 실행의 **시작 중 최대 RSS 161.8 MiB, 색인/검색 중 최대 RSS 228.4 MiB**(233884 KiB). Linux `/proc`에서 **애플리케이션 JVM만** 50ms 간격으로 측정했다. Gradle·Node 빌드 프로세스는 런타임 측정에 포함하지 않았다.
- 한국어 질의 80조각, PDF 영문 질의는 2쪽 결과 20개, 추가 검색 30회, MCP 초기화·도구 목록·두 도구 호출·토큰 폐기를 검증했다. 실행 스크립트 종료 코드 0.
- **Spring Boot 유지 의견:** 이번 소형 문서 MVP는 512 MiB 목표에 충분한 RSS 여유가 있어 유지한다. 실제 cgroup 제한, 파일 캐시·컨테이너 전체 사용량, 큰 PDF·대규모 소스·동시 요청에 대한 보장은 아니므로 배포 전 별도 부하 검증이 필요하다.
- 상세: `docs/verification/mvp.md`. 재현: `VERIFY_PORT=8101 python3 scripts/verify-runtime.py`. 원시 결과는 무시되는 `build/verification/`.

### 남은 일·검증 한계

- [ ] Docker가 없는 환경이므로 이미지 빌드·실제 512 MiB cgroup·비루트 PUID/PGID 볼륨 권한·amd64/arm64 실행 검증.
- [ ] 실제 NAS 대용량 자료·큰 PDF/손상 PDF·다중 클라이언트 부하와 파일 캐시 포함 메모리 측정.
- [ ] TOTP, 로그인 시도 제한, 관리자 복구 절차/UI, MCP OAuth(후속 보안 작업).
- [ ] 실제 브라우저에서 모바일·라이트/다크·접근성 시각 검증(이번 환경에는 브라우저 자동화 도구 없음). 프런트엔드는 TypeScript/Vite 빌드와 정적 리소스 제공을 검증했다.
- [ ] 검색 하이라이트, 색인 오류 원인 상세 UI, 파일별 영속 변경 감지 스냅샷 최적화.
- [ ] 벡터/임베딩·리랭커·OCR·추가 문서 형식은 기존 2~4단계에서 구현.
