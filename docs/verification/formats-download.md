# 3단계 형식·다운로드 검증

2026-10-24, 키워드 모드(`embedding=off`). 공개 기록에는 장비 이름·계정·로컬 절대 경로·인증값·서명 주소를 넣지 않았다.

## 명령과 결과

| 명령 | 종료 코드 | 결과 |
| --- | --- | --- |
| `./gradlew :core:generateSamples -q` | 0 | 기존 Markdown 20개·2쪽 PDF 20개 + DOCX/DOC/PPTX/PPT/XLSX/XLS/HWP/HWPX 각 1개, 총 48개 |
| `./gradlew :core:test -q` | 0 | 8형식 자체 생성·추출/색인 왕복·근거·문자 상한·오류 상태 검사 |
| `./gradlew :app:test -q` (수정 후) | 0 | REST·토큰 범위·MCP 세 도구·서명 다운로드·기존 원격 임베딩 회귀 |
| `./gradlew build -q` (최종) | 0 | core 테스트 11개, app 통합 테스트 1개, 프런트엔드 포함 전체 빌드 |
| `VERIFY_PORT=8121 python3 scripts/verify-runtime.py` (최종) | 0 | curl 전체 검증, RSS 기록, JVM/bootRun 종료·포트 반환 |
| `python3 -m py_compile scripts/smoke.py scripts/verify-runtime.py` | 0 | Python 문법 검사 |
| `git diff --check` | 0 | 공백/패치 오류 없음 |

## 검증 범위

- Word는 XWPF/HWPF, PPT는 XSLF/HSLF, XLSX는 SAX XSSFReader, XLS는 HSSF 이벤트, 한글은 hwplib/hwpxlib로 읽는다. 생성용 XLSX도 SXSSF를 사용한다. 외부 문서/샘플을 복사하지 않았으며 DOC는 최소 Word 97 FIB·UTF-16 piece table을 직접 작성했다.
- 모든 새 형식 고유 검색어가 검색되고 `get_document` 텍스트와 근거가 일치한다. PDF 20개의 2쪽 검색 결과는 기존과 동일하다. PPT 두 번째 슬라이드, Excel `자료` 시트의 5행(중간 빈 행 포함), Word/HWP 문단 번호를 확인했다. 근거는 Lucene 저장·조회 후에도 유지된다.
- 8형식 손상 파일, 암호화 DOCX/PPTX/XLSX(agile)·XLS, HWP 암호 플래그, OOXML/HWPX 압축률 bomb, ZIP 항목 1025개, 외부 엔티티 DOCTYPE, 8 MiB 초과 파일을 거부한다. 같은 색인 작업에서 정상 8파일 검색은 유지하고 실패 파일을 오류 수에 포함한다.
- REST와 MCP `download_file` 발급, 익명 curl 수신의 원본 바이트 일치, HMAC 위조 거부, 토큰 소스 범위 밖/상대경로 탈출/절대경로 차단을 확인했다. 발급 후 파일을 소스 밖 심볼릭 링크로 교체한 경우 수신·재발급 모두 거부한다. 한글·공백·따옴표 파일 이름은 RFC 5987 헤더로 전달한다.
- 테스트 시계로 발급 후 정확히 300초에 만료 거부, `nasquire.public-url` 접두 경로 적용, 감사 로그에 API 토큰·서명 티켓이 없음을 확인했다. HTTP 수신은 Bearer와 관리자 세션 없이 성공하며 주소 발급은 인증이 필요하다.
- 기존 관리자 세션·CSRF·토큰 발급/폐기·MCP 초기화/도구 목록·검색/문서 조회·원격 임베딩 테스트를 유지했다. REST/MCP가 같은 권한·위치 구현을 사용한다.

## RSS 실측

| 항목 | 값 |
| --- | --- |
| 문서 | 48파일, PDF 40쪽, 새 형식 8파일 |
| JVM heap | 64 MiB 시작 / 256 MiB 최대 |
| GC | SerialGC |
| Metaspace / code cache / direct memory | 최대 128 / 48 / 32 MiB |
| 시작 중 최대 RSS | 169928 KiB (165.9 MiB) |
| 색인·검색·다운로드 중 최대 RSS | 281656 KiB (275.1 MiB) |
| 측정 | 애플리케이션 JVM `/proc` RSS, 50ms 간격 |
| 실행 종료 | 스크립트 0, JVM/bootRun 프로세스 정리, 8121 포트 닫힘 |

Gradle·Node·샘플 생성 JVM은 RSS 대상에서 제외했다. 48문서 색인 후 새 형식 8개 검색/문서 조회, PDF/한국어 검색, REST/MCP 다운로드, 추가 검색 30회 등을 수행했다. 측정값은 512 MiB보다 작지만 실제 cgroup·파일 캐시·동시 클라이언트를 포함한 보장은 아니다. 내장 임베딩은 켜지 않았으며 기존 초과 결과는 [2단계 기록](vector.md)에 남아 있다.

## 실패 이력과 미검증

첫 다운로드 통합 테스트는 위조 주소를 거부하면서 HTTP 401을 반환해 기대 403과 달랐다(테스트 태스크 종료 코드 1). 접근 거부 응답을 명시하여 후속 테스트·전체 빌드는 통과했다. 최초 런타임은 DOC에도 `Second` 검색어가 있어 PDF 전용 결과 수 검사가 실패했다(종료 코드 1). PDF 확장자 필터를 추가한 최종 실행은 통과했으며 실패 실행도 서버를 종료했다. 샘플 생성 JVM의 Log4j provider 안내와 최소 DOC의 빈 CHPX/PAPX 안내는 남지만 추출/색인은 성공했다.

실제 편집기에서 생성 샘플 열기, 암호화 DOC/PPT/HWPX 실물, 실제 NAS 큰 자료·매우 큰 공유 문자열/중첩 표·악성 바이너리 퍼징, Docker 512 MiB cgroup·arm64 실행은 검증하지 않았다. 해당 환경/자료가 준비되지 않았다. 추출에 보수적인 상한이 있어 큰 정상 파일도 오류가 될 수 있다. 다운로드는 심볼릭 링크를 모두 거부하고 SecureDirectoryStream 미지원 파일시스템에서 실패한다. 이미 발급한 링크는 API 토큰 폐기와 별개로 짧은 만료까지 유효하며 서버 재시작 시 무효화된다.

원시 아티팩트는 무시되는 `build/verification/measurement-off.json`, `smoke-off.txt`, `bootrun-off.log`에 있으며 테스트 상세는 `core/build/reports/tests/test/`, `app/build/reports/tests/test/`에 있다. 원시 실행 로그와 테스트 보고서는 로컬 정보가 있을 수 있어 공개 문서로 복사하지 않는다.
