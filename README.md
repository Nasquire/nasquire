# Nasquire

**Ask your NAS.** Search existing folders on a NAS or server through a browser, REST API, or MCP client. Nasquire reads ordinary sources without modifying them and keeps metadata and its search index in a separate data directory. Optional upload sources use dedicated writable folders; external shares expose only one selected file or folder.

Copyright (c) 2026 Bluesounds. [Source repository](https://github.com/Nasquire/nasquire).

The search service supports PDF (page-level text), UTF-8 and detected-encoding text, Markdown, Word (.docx/.doc), PowerPoint (.pptx/.ppt), Excel (.xlsx/.xls), and Korean HWP/HWPX. Excel uses SAX/event readers. Korean Nori analysis also handles English terms; Lucene ranks chunks with BM25. Optional local ONNX and remote embeddings add Lucene HNSW vector search and BM25/vector RRF fusion. 선택 full 모듈은 OCR·리랭커를 제공하고 관리자 TOTP도 지원합니다. See [embedding configuration](docs/embeddings.md).

## Quick start

Official images and support cover **Linux amd64 (64-bit)** only. ARM, including arm64 and 32-bit ARM, has no official image or support. The code remains architecture-neutral; arm64 users can build locally with `docker build --target lite .`. Historical [lite/full measurements on an arm64 Linux VM](docs/verification/deploy.md#arm64-컨테이너-재측정-이름-전달-수정-후-기준-d8e83a5) are provided for reference; subsequent changes have not been verified on arm64.

Install a Java 25 JDK. Gradle downloads Node for the frontend; a separate Node installation is unnecessary. The first build requires Internet access.

```sh
./gradlew build
./gradlew :app:bootRun
```

Open `http://localhost:8080`, create the administrator (password: at least 8 characters, including an ASCII letter, digit and printable ASCII punctuation, at most 72 UTF-8 bytes; both letter cases are not required), and sign in. In **Sources**, register an existing readable folder, wait for two scans, then use **Search playground**. Create a read-only API token in **API tokens**; its value is displayed once. The UI selects Korean or English from the browser language and supports system, light, and dark themes.

The app uses `./data` by default. Stop the application before copying its data directory for backup; restore the entire directory together. Sources can always be reindexed. Only one application instance may use a data directory.

## 선택 full 모듈

기본 빌드는 lite이며 OCR·리랭커는 off입니다. `./gradlew -Pfull build -q` 또는 `./gradlew -Pfull :app:bootRun`으로 ONNX 임베딩·원격 리랭커·외부 Tesseract OCR 구현을 포함합니다. 기존 내장 임베딩도 이제 full 빌드가 필요합니다. 원격 임베딩은 lite에서 사용할 수 있습니다. 두 빌드의 앱 JAR 경로가 같으므로 마지막 빌드 구성을 확인하세요.

full 이미지에는 Tesseract 실행 파일과 kor+eng 데이터가 포함됩니다. JAR를 직접 실행한다면 별도로 준비해야 합니다. 관리 설정 API와 제한은 [full 검증](docs/verification/full.md)을 참고하세요. 선택 Qdrant 벡터 저장소(임베딩 설정), 로컬 ONNX 리랭커(`reranker=builtin`, full), 임베딩·리랭크·OCR만 제공하는 ML 전용 모드(`--nasquire.mode=ml`, full, Bearer 필수)를 제공합니다. 실제 OCR 비교·PaddleOCR·실제 Qdrant 서버 검증은 미완료입니다. Dockerfile은 `lite`·`full` 타깃을 제공하며 기본 타깃은 lite입니다.

## Docker 빠른 시작

공식 Linux amd64(64비트) 이미지는 `ghcr.io/nasquire/nasquire:lite`(기본·`:latest`)와 `:full`입니다. 첫 릴리스 게시 전에는 pull이 실패할 수 있으며 이번 단계에서는 게시하지 않았습니다.

arm64는 공식 이미지·지원이 없습니다. 코드는 아키텍처 중립으로 유지하며 `docker build --target lite .`로 직접 빌드할 수 있습니다. 과거 arm64 Linux VM의 lite/full 동작 [실측 기록](docs/verification/deploy.md#arm64-컨테이너-재측정-이름-전달-수정-후-기준-d8e83a5)은 참고용이며 이후 변경은 검증하지 않았습니다. 32비트 ARM도 공식 지원하지 않습니다. 실제 NAS·대규모 부하는 미검증입니다. CI·이미지 빌드는 공개 저장소에서 실행 성공했습니다([기준 cd6244b](https://github.com/Nasquire/nasquire/commit/cd6244b)). GHCR 게시는 미실시입니다.

데이터 폴더를 실행 UID/GID가 쓸 수 있게 먼저 준비하고 HTTPS 프록시를 구성하세요. 일반 문서는 읽기 전용으로 연결합니다.

```sh
export DATA_DIR=/srv/nasquire/data SOURCE_DIR=/srv/documents
export PUID=1000 PGID=1000 PUBLIC_URL=https://nas.example.com
# TRUSTED_PROXIES에는 앱에 실제 연결하는 프록시 IP만 설정합니다.
docker compose -f deploy/docker-compose.yml up -d
# 이미지 게시 전 로컬 빌드: 위 명령에 --build 추가
```

`/sources/documents`를 UI 소스로 등록합니다. lite 예시는 512 MiB 한도이며 내장 모델·리랭커에는 부족합니다. full은 `-f deploy/docker-compose.full.yml`을 추가하고, 같은 full 이미지의 ML 전용 모드는 `deploy/docker-compose.ml.yml`을 사용합니다. 모델은 이미지에 포함하지 않습니다. PUID/PGID는 compose의 숫자 `user` 매핑을 통해 적용하며, 공개 이미지에 환경변수만 주어서는 사용자가 바뀌지 않습니다.

- [Docker 공통 설치·HTTPS·백업·업그레이드·관리자 복구](docs/install/generic-docker.md)
- 선택 외부 로그인 / Optional external sign in: [OIDC 설정 (한국어·English)](docs/install/oidc.md). 기본 off, 로컬 로그인 유지 / Off by default; local sign in remains available.
- [Synology Container Manager](docs/install/synology.md) · [Unraid와 XML 템플릿](docs/install/unraid.md) · [TrueNAS SCALE](docs/install/truenas.md)
- [배포·CI 검증과 미실측 항목](docs/verification/deploy.md) · [full 메모리 실측](docs/verification/full.md)

## 최소·권장 사양

공식 지원 플랫폼은 Linux amd64(64비트)입니다. ARM 전반은 공식 미지원입니다. 아래 메모리는 컨테이너 제한이며 호스트 OS·다른 서비스의 여유 메모리는 별도로 확보하세요.

| 구성 | 최소: CPU / 메모리 제한 / 디스크 | 권장: CPU / 메모리 제한 / 디스크 |
| --- | --- | --- |
| lite 키워드 검색(힙 192 MiB) | 1코어 / 512 MiB / 이미지 + 데이터 | 2코어 / 1 GiB / 이미지 + 데이터용 SSD·여유 공간 |
| lite + 원격 임베딩 | 1코어 / 512 MiB / 이미지 + 데이터·벡터 색인 | 2코어 / 1 GiB / 데이터용 SSD·벡터 증가분 여유 |
| full OCR(내장 모델 off) | 1코어 / 1 GiB / 이미지 + 데이터·OCR 임시 공간 | 2코어 / 2 GiB / 데이터용 SSD·OCR 임시 공간 |
| full + 내장 임베딩·로컬 리랭커 | 2코어 / 1.5 GiB / 이미지 + 데이터·모델 | 4코어 / 3~4 GiB / 데이터·모델용 SSD |
| ML 전용 서버(full 이미지) | 2코어 / 2 GiB / 이미지 + 모델·OCR 임시 공간 | 4코어 / 3~4 GiB / 모델·OCR 임시 공간용 SSD |

lite 사양 참고 실측(자체 PDF 18개×360쪽 + Office/HWP 8개, 단독 cgroup 제한 없음):

| 앱 메모리 | 기존 힙 256 MiB | 개선 힙 192 MiB·유휴 수축 |
| --- | ---: | ---: |
| 최대 RSS | 426.4 MiB | 410.9 MiB |
| 색인 후 대기 RSS | 426.4 MiB | 362.4 MiB |

CPU·권장 메모리는 소규모 운영을 위한 시작 기준이며 처리량 보장값은 아닙니다. **최소 지원 기준은 512 MiB**입니다. 참고: arm64 VM 실측에서 lite는 512/384/320 MiB 제한 스모크를 통과했으나, 이는 과거 소규모 시험 기록이며 amd64 메모리 보장값이 아닙니다. 원격 임베딩의 공급자 비용·서버 자원은 별도입니다.

참고: arm64 VM 실측 이미지 크기는 lite 약 638 MB, full 약 899 MB였습니다. 공식 amd64 이미지 크기는 별도 확인이 필요합니다. 디스크는 이미지 크기에 `/data`의 DB·색인·로그·백업 여유와 선택 업로드/휴지통을 더하고, 모델을 쓰면 실제 모델 파일 크기와 다운로드 임시 공간을 더합니다. 색인은 원본 파일 개수/바이트만으로 산정할 수 없고 추출 텍스트 양·벡터 수에 비례합니다. OCR은 작업당 PNG 합계 상한 256 MiB 외에 원본 복사·PDF scratch 공간이 필요합니다.

내장 E5 검색 서버의 기존 최대 RSS는 855.7 MiB이며 로컬 리랭커/ML 서버도 512 MiB를 초과했습니다. ML 두 모델 실측은 1047.8 MiB입니다. full OCR의 1 GiB는 과거 arm64 VM의 소규모 PDF·PNG 성공 기록을 참고한 시작 기준이며 amd64 컨테이너 보장값은 아닙니다. 1.5 GiB/2 GiB도 모델·OCR 동시 부하를 보장하지 않습니다. 위 lite 실측의 조건·NMT·공유 cgroup 수치는 별도 기록했습니다. 단독 512 MiB cgroup 및 개선 후 NAS 실기 재측정은 남아 있습니다([메모리 측정](docs/verification/memory.md)).

실측 범위와 소규모 디스크 예시는 [배포 검증](docs/verification/deploy.md)을 참고하세요.

## Configuration

Pass Spring Boot arguments to `bootRun` or the executable JAR:

```sh
./gradlew :app:bootRun --args='--server.port=8101 --nasquire.data=./data --nasquire.scan-millis=300000'
java --enable-native-access=ALL-UNNAMED -Xms64m -Xmx192m -XX:+UseSerialGC -XX:+UseCompactObjectHeaders -XX:MaxHeapFreeRatio=20 -XX:MinHeapFreeRatio=10 -XX:-ShrinkHeapInSteps -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:MaxDirectMemorySize=32m -jar app/build/libs/app-0.1.0.jar --nasquire.data=./data
```

| Property | Default | Purpose |
| --- | --- | --- |
| `nasquire.data` | `./data` (`/data` in Docker) | H2 metadata and Lucene index; environment variable `NASQUIRE_DATA` |
| `server.port` | `8080` | HTTP port; environment variable `PORT` |
| `nasquire.scan-millis` | `300000` | Initial scan interval; saved administrator settings take priority |
| `nasquire.stable-millis` | `2000` | Minimum unchanged size/mtime observation period |
| `nasquire.max-file-bytes` | `33554432` | File-size ceiling (32 MiB) |
| `nasquire.max-text-characters` | `2000000` | Extracted-character ceiling, 1200–4000000; Office/HWP indexes the prefix at this limit |
| `nasquire.mcp-allowed-origins` | empty | Comma-separated exact allowed browser Origins for MCP; no Origin header is required for native clients |
| `nasquire.public-url` | empty | Public base URL, e.g. `https://nas.example.com`; otherwise return a relative path (MCP responses recommend configuring this value) |
| `nasquire.download-ttl-seconds` | `300` | Opaque download ticket lifetime, 1–3600 seconds |
| `nasquire.trusted-proxies` | empty | Comma-separated exact proxy IP addresses permitted to supply X-Forwarded-For; no DNS names or CIDR |
| `nasquire.upload-max-bytes` | `33554432` | Upload ceiling, 1 byte–1 GiB; multipart stored on disk, request ceiling includes 1 MiB overhead |
| `nasquire.trash-retention-days` | `30` | Trash retention, 1–365 days; hourly cleanup, first run after one minute |
| `nasquire.share-requests-per-minute` | `60` | Public requests per remote IP, 1–10000; at most 4096 IP windows |
| `server.servlet.session.cookie.secure` | `false` | Set `true` when serving through HTTPS |

Include/exclude globs use paths relative to the source, one per line in the UI. An empty include list includes all supported formats. `**/*.pdf` includes root-level PDFs too. Default exclusions always apply: `@eaDir`, `#recycle`, `#snapshot`, `.DS_Store`, `Thumbs.db`, `~$*`, `.~*`, backup `*~`, and `.tmp`, `.temp`, `.part`, `.swp`, `.swo`, `.crdownload` files. Directory symlinks are skipped; file symlinks are read only when their resolved target stays inside the source. Returned relative names use NFC normalization. Source and data directories must not overlap.

One low-priority worker scans and indexes sequentially. Size/mtime changes and deletions are reflected on later scans. Changes preserving both values require **Reindex**. Failed extraction is reported in source status and retried. A restart rebuilds source indexes after stability checks. Text is split into 1200-character chunks without overlap; PDF page numbers start at 1. The `location` field records PDF `page`, PowerPoint `slide`, Excel `sheet`/`rowFrom`/`rowTo`, and Word/HWP `paragraph`/optional `heading`. Existing `page` is a compatibility field (extraction order for new formats); use `location` for citations. Paragraph locations do not imply printed pages. Scanned PDFs require full OCR; historical arm64 VM tests confirmed the image-only PDF fix, but subsequent changes have not been verified on arm64. Search returns chunk text without HTML highlighting.

Office/HWP parsing additionally caps originals at 8 MiB, ZIP entries at 1024, inflated entries at 8 MiB and total inflated bytes at 16 MiB, with a minimum 1% compression ratio. XML depth is capped at 64 and nodes at 100000 per entry; HWP total expanded streams are capped at 2 MiB. Files exceeding these limits, encrypted or corrupt files, and documents with over 4096 nonempty extraction units are reported as errors. XLSX shared strings/styles and XLS SST remain in bounded memory. Formula values come from saved results; formulas are never executed. These conservative limits trade large-document coverage for the 512 MiB runtime goal.

## REST API

Authenticate reading requests with `Authorization: Bearer <token>` or an administrator session. Tokens cannot manage sources or credentials. Their SHA-256 digests are stored, and requests are audited. Empty token scope means **all current and future sources**. A restricted token can access only its selected source IDs. Expiry is mandatory; revocation takes effect on the next request.

| Method | Endpoint | Use |
| --- | --- | --- |
| GET | `/api/auth` | Setup/login state and CSRF token/header; also initializes an anonymous session |
| POST | `/api/setup` | Create the sole administrator with JSON `username`, `password` |
| POST | `/api/login` | Form-encoded `username`, `password` |
| POST | `/api/logout` | End administrator session |
| GET, POST | `/api/sources` | List/register sources (`name`, `path`, `includes`, `excludes`, optional `kind`: `directory` or `upload`) |
| PUT, DELETE | `/api/sources/{id}` | Update/remove a source |
| POST | `/api/sources/{id}/reindex` | Queue a complete rebuild |
| GET | `/api/sources/{id}/status` | State, indexed/pending file counts, errors, up to five recent relative paths and safe failure reasons (administrator session only) |
| GET, POST | `/api/tokens` | List/issue tokens (`name`, `expiresAt` in Unix milliseconds, `sources` ID array) |
| DELETE | `/api/tokens/{id}` | Revoke a token |
| GET | `/api/audit` | Most recent 200 events (10,000 retained) |
| GET | `/api/search?query=...&source=...&limit=20` | Search; optional source ID and limit 1–100 |
| GET | `/api/document?path=...` | Read indexed text using a search result's source-qualified path, including `locations` |
| GET | `/api/download?path=...` | Issue `{url, expiresAt}` for an original file; authenticated and source-scoped |
| GET | `/download?ticket=...` | Fetch original bytes without Bearer; requires an unexpired valid HMAC signature |

Administrator mutations, including setup, login, and logout, require the CSRF header from `/api/auth` and the same session cookie. Login rotates the session and CSRF token; fetch `/api/auth` again afterward. Document requests accept `page_from`/`page_to` **or** `offset`/`length` (zero-based character offset, at most 100000 characters). Responses include `truncated` and `totalCharacters`. The path format is `<source-id>:<relative-name>`; arbitrary filesystem reads are never exposed. Source removal makes its results inaccessible immediately, then the worker removes its index entries.

Download links expire after five minutes by default and are invalidated by server restart. Token revocation, expiration, and source scope reductions invalidate already issued links at receipt. Ticket IDs are random; paths and token IDs stay in bounded server memory (10,000 active tickets maximum). Source existence, include/exclude rules, real paths, and directory handles are rechecked at receipt. Downloads reject all symbolic links, including internal ones, and fail closed where secure directory streams are unavailable. Originals are opened read-only. Responses use attachment disposition with an RFC 5987 UTF-8 filename and disable caching. Download tickets resolve the current original, not a pinned content version. External shares also bind the shared root to its filesystem file key. Set `nasquire.public-url` behind a reverse proxy. Do not log download query strings in proxy access logs; application audit records omit tickets and signatures. External shares and upload sources are configured separately below.

## 업로드 소스·외부 공유

기본 소스 종류는 `directory`이며 읽기 전용입니다. **Sources**에서 `upload`를 명시하고 데이터 폴더 및 다른 모든 소스와 겹치지 않는 별도 쓰기 가능 폴더를 지정하면 업로드 기능이 활성화됩니다. 일반 소스 마운트는 `:ro`로 유지하고 업로드 폴더만 별도 `:rw`로 연결하세요. 등록한 소스의 종류·경로 변경은 등록 해제 후 다시 추가합니다. 앱만 업로드 폴더를 관리하도록 파일시스템 권한을 제한하세요.

**파일·공유 / Files and shares**에서 업로드 폴더 탐색·업로드·폴더 생성·소스 안 이동·이름 변경·휴지통·복원을 수행합니다. 덮어쓰기는 명시적으로 선택해야 합니다. 파일 이름은 NFC이며 금지 문자·예약 이름·240 UTF-8 바이트 상한을 검사합니다. 심볼릭 링크는 거부합니다. `.nasquire-*` 임시 파일·휴지통은 색인·공유에서 제외합니다. 파일 업로드 후 다음 색인 작업을 즉시 예약합니다. 멀티파트는 데이터 폴더의 임시 파일로 받고, 64 KiB 버퍼로 업로드 폴더 안 임시 파일에 복사한 후 원자적으로 이동합니다. 업로드 상한과 기존 색인 상한은 독립적이므로 업로드에 성공한 큰 파일도 색인에서 제외되거나 오류가 될 수 있습니다.

안전한 폴더 생성·덮어쓰기 없는 원자적 이동은 Java 25 FFM과 Linux libc `openat`/`mkdirat`/`renameat2`를 사용합니다. Linux amd64/arm64와 SecureDirectoryStream 지원 파일시스템이 필요합니다. 미지원 시 실패하며 경로 기반의 불안전한 쓰기로 대체하지 않습니다. bootRun·컨테이너에는 native access 옵션이 포함되어 있습니다. arm64 네이티브 구현은 직접 빌드 사용자를 위해 유지하지만 공식 지원하지 않습니다. 실제 NAS 파일시스템은 미검증입니다.

공유는 일반 소스와 업로드 소스에서 파일 하나 또는 폴더 하나를 선택합니다. 경로는 소스 기준 상대경로이며 빈 경로는 소스 루트입니다. 만료 기본값은 7일(최대 365일), 비밀번호는 선택(8자 이상·UTF-8 72바이트 이하), 다운로드 상한은 선택(1–1000000)입니다. 256비트 무작위 `/s/{token}` 주소는 생성할 때 한 번만 표시하며 DB에는 SHA-256만 저장합니다. 비밀번호는 BCrypt 비용 12로 저장합니다. 비밀번호 링크는 브라우저의 HTTP Basic 인증 창을 사용합니다(사용자명은 비워 두고 비밀번호만 입력). HTTPS로 제공하세요.

공개 경로는 관리 세션 쿠키를 사용하거나 생성하지 않습니다. 폴더 하위 목록·탐색·다운로드만 제공하며 검색·MCP·소스 경로·상위 폴더는 제공하지 않습니다. 매 요청에 소스·제외 규칙·실경로·NOFOLLOW 검사를 적용하고 삭제·제외·철회·만료·횟수 소진 대상은 404입니다. 다운로드 횟수는 응답 전송 시작 시 소비하므로 중단된 전송도 포함합니다. 비밀번호 실패는 1/2/4/8초 지연 후 5회째 15분 제한하며 공유+IP 단위로 적용됩니다. IP 제한은 기본 연결 주소 기준이며 `nasquire.trusted-proxies`로 지정한 프록시만 전달 헤더를 사용할 수 있습니다. 4096개 항목을 넘으면 LRU를 제거합니다. 기본 CSP·nosniff·no-referrer·no-store·DENY 헤더를 적용합니다.

| Method | Endpoint | 용도 |
| --- | --- | --- |
| GET, POST | `/api/uploads/{id}` | 상대 `path` 폴더 탐색 / multipart `file` 업로드 (`path`, 선택 `overwrite=true`) |
| POST | `/api/uploads/{id}/folder` | JSON `{path}` 폴더 생성 |
| POST | `/api/uploads/{id}/move` | JSON `{path,destination,overwrite}` 이름 변경·이동 |
| GET, POST | `/api/uploads/{id}/trash` | 휴지통 목록 / JSON `{path}` 휴지통 이동 |
| POST | `/api/uploads/{id}/restore/{item}` | 원래 경로로 복원(기존 대상이 있으면 실패) |
| GET, POST | `/api/shares` | 공유 목록 / JSON `{source,path,expiresAt?,password?,maxDownloads?}` 생성 |
| DELETE | `/api/shares/{id}` | 즉시 철회 |
| GET | `/s/{token}?path=...&download=true` | 익명 공유 방문·다운로드, 선택 HTTP Basic 비밀번호 |

모든 관리 변경은 관리자 세션+CSRF가 필요하고 API 토큰·MCP에는 쓰기를 제공하지 않습니다. 감사 로그에는 공유 토큰·비밀번호를 넣지 않습니다. 프록시 접근 로그에서도 `/s/` 토큰 경로·Authorization·서명 쿼리를 제외하세요. 업로드 소스를 등록 해제하면 휴지통 메타데이터를 제거하고 공유를 철회하지만 숨김 휴지통 파일은 남기므로 먼저 복원/정리하세요. 24시간 지난 임시 파일은 재시작 시 정리하며 파일 이동/DB 사이 중단 복구에는 운영자 확인이 필요합니다. [업로드·공유 검증과 남은 한계](docs/verification/upload-share.md)를 참고하세요.

## MCP connection

The official MCP Java SDK provides stateless Streamable HTTP at `/mcp`. Every request requires a Bearer token, including `initialize` and `tools/list`. Administrator session cookies alone are insufficient. No persistent MCP session ID is needed. Configure your HTTP-capable MCP client with the URL and authorization header; use its secret/environment storage for the token.

```json
{
  "mcpServers": {
    "nasquire": {
      "type": "http",
      "url": "https://nasquire.example.com/mcp",
      "headers": { "Authorization": "Bearer <your-issued-token>" }
    }
  }
}
```

Client configuration syntax varies. The server exposes:

- `search_documents(query, source?, limit?, mode?)`: chunk text, source ID/name, source-qualified path, page, `location`, offset, mtime, and score.
- `get_document(path, page_from?, page_to?, offset?, length?)`: indexed text and location evidence; page/order and character ranges are mutually exclusive.
- `download_file(path)`: short-lived signed original-file URL and `expiresAt` (Unix milliseconds), subject to the same source scope as search.

A direct tool request after initialization:

```sh
curl https://nasquire.example.com/mcp \
  -H "Authorization: Bearer $NASQUIRE_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2025-11-25' \
  --data '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_documents","arguments":{"query":"ocean","limit":5}}}'
```

## Verification and operating limits

```sh
./gradlew build -q
# Linux, Python 3 and curl; an available development port, new temporary metadata:
VERIFY_PORT=8101 python3 scripts/verify-runtime.py
```

The runtime check generates 48 original sample documents (including all eight new formats), starts `:app:bootRun`, exercises setup → indexing → Korean/English search → tokens → all three MCP tools → anonymous signed-download curl, also streams a 16 MiB upload, checks uploaded-file search, trash/restore and anonymous external shares, records JVM RSS, and stops the server. Set `VERIFY_EMBEDDING=builtin` to fetch pinned SHA-256-verified model files into ignored build artifacts and test local hybrid search, or `VERIFY_EMBEDDING=remote` to exercise a fake HTTP provider. Do not run builds concurrently with bootRun verification because they share class output directories. It writes ignored artifacts under `build/verification`. [Stage 3 results and limits](docs/verification/formats-download.md); [MVP results](docs/verification/mvp.md).

Use HTTPS and restrict initial setup/administrator access to trusted users. Secure session cookies should be enabled behind HTTPS. This MVP has one administrator, optional TOTP and offline administrator recovery; MCP OAuth is optional and requires explicit enablement, an HTTPS public URL, and administrator source approval. A sampled RSS measurement on small documents is not a guarantee for large PDFs or concurrent workloads; test your own corpus under a real container limit. The source mount must be read-only to enforce isolation against filesystem races.

## License

[GNU AGPL v3.0](LICENSE) with [additional terms](LICENSE-EXCEPTIONS.md):

- Personal and in-company use and modification: free, no obligation to publish source.
- Free, non-commercial distribution or hosting: also exempt from publishing source.
- Selling it, or offering it as part of a paid product, subscription, or ad-gated service: AGPL applies — publish your source.
- Always keep the attribution to Nasquire.

[Third-party dependency licenses](THIRD-PARTY.md). The original license files and notices are preserved in dependency JARs.


## 관리자 2단계 인증·오프라인 복구

**사이드바 하단 톱니바퀴 / Settings**를 누르면 중앙 환경설정 모달이 열립니다(모바일 전체 화면). 계정 → 2단계 인증 → 검색 → full 전용 리랭커·OCR → 정보 순서이며 맨 아래에서 로그아웃합니다. 하단 텍스트 버튼으로 자동·라이트·다크 테마를 전환합니다. 계정에서 현재 비밀번호를 확인하고 관리자 비밀번호나 사용자명(영문·숫자·_.- 3~100자)을 변경합니다. TOTP가 켜져 있으면 사용하지 않은 인증 앱 코드도 필요합니다. 성공하면 세션 ID·CSRF를 교체하고 다른 세션을 무효화합니다. 정보 섹션의 라이선스 보기에서는 원본 링크를 유지하며 AGPL·추가 허가·React 라이선스를 탭으로 읽습니다.

환경설정에서 검색(임베딩 공급자·Lucene/Qdrant 벡터 저장소·연결 시험·재색인 상태), 리랭커, OCR을 관리합니다. 검색 설정과 리랭커·OCR 설정은 각각 저장하며 기존 키 비공개·키 삭제·HTTP 경고·재색인 정책을 유지합니다. lite에서는 원격 임베딩과 키워드 검색만 표시하며 내장 ONNX 선택지·리랭커·OCR은 숨깁니다. 현재 버전과 lite/full 빌드는 정보 섹션에서 확인합니다. API 토큰 화면에서는 폐기·만료 토큰을 개별 또는 일괄 삭제할 수 있습니다. 활성 토큰은 먼저 폐기해야 하며 감사 기록은 유지됩니다.

**검색 시험 / Search playground** 결과는 소스 이름과 상대 경로를 표시합니다. 입력 질의의 문자·숫자 토큰을 대소문자 구분 없이 강조하고, 검색어 주변 최대 360자 발췌와 생략 표시를 제공합니다. 공백과 빈 줄은 축약하며 **더 보기 / Show more**로 전체 청크를 펼칩니다. 강조할 일치가 없는 벡터 검색 결과는 청크 앞부분을 표시합니다.

The sidebar settings gear opens a central dialog (full screen on mobile): Account, Two-step verification, Search, full-only reranker/OCR, About, and Sign out. Auto/Light/Dark buttons stay in the sidebar footer. Account changes require the current password and, when enabled, an unused authenticator code; they rotate the session and CSRF token and invalidate other sessions. About includes license tabs and original file links. Revoked or expired API token records can be deleted individually or together; audit events remain.

**환경설정 → 2단계 인증 / Settings → Two-step verification**에서 선택적으로 TOTP를 켭니다(기본 꺼짐). 등록에는 현재 비밀번호 재확인이 필요하며, 등록 시 한 번 표시되는 Base32 비밀 또는 `otpauth://` URI를 인증 앱에 입력하고, 10분 안에 코드 한 번을 확인해야 활성화됩니다. QR 생성 의존성은 추가하지 않았습니다. SHA-1·6자리·30초, 서버 시각 기준 ±1단계를 허용하며 이미 사용한 단계 이하의 코드는 거부합니다. 서버와 인증 기기의 시간을 동기화하세요. 비밀은 데이터 폴더의 기존 `embedding.key`를 사용하는 AES-256-GCM으로 DB에 암호화되며 조회 API로 다시 반환하지 않습니다. 데이터 폴더와 키를 함께 보호·백업하세요. 키 파일이 없거나 바뀌었는데 암호문이 남아 있으면 새 키를 만들지 않고 오류를 기록합니다. 이 경우 키를 백업에서 복원하거나 아래 오프라인 복구로 TOTP를 해제한 뒤 임베딩 원격 키를 다시 저장하세요(복구 코드 로그인은 키 없이도 가능합니다).

활성화 때만 표시되는 일회용 복구 코드 10개를 별도 안전한 곳에 보관하세요(DB에는 SHA-256만 저장). 비밀번호 로그인 뒤 인증 앱 코드 또는 복구 코드를 입력해야 관리 권한을 받습니다. 대기 세션은 5분 후 만료되며 코드 실패도 기존 IP/계정별 지연·잠금과 감사에 포함됩니다. 같은 대기 세션에서 5회 실패하면 비밀번호 단계부터 다시 시작해야 하며, 코드 단계의 **취소**로도 비밀번호 단계로 돌아갈 수 있습니다. 복구 코드는 대소문자·공백·하이픈을 무시합니다. 등록·해제 중 실패는 로그인과 별도로 제한되어 관리자 로그인을 잠그지 않습니다. 해제에는 **현재 비밀번호와 아직 사용하지 않은 인증 앱 코드**가 모두 필요하며 복구 코드는 해제에 사용할 수 없습니다. 해제하면 남은 복구 코드도 폐기됩니다. 기존에 완료된 다른 관리자 세션은 유지되므로 분실·침해가 의심되면 오프라인 복구를 사용하세요. API Bearer 토큰·MCP 인증은 별도로 유지됩니다.

비밀번호 또는 TOTP를 잃었을 때는 데이터 폴더에 접근할 수 있는 운영자가 **앱을 종료한 상태**에서 같은 버전의 실행 JAR로 아래 명령을 실행합니다. 네트워크 복구 API는 없으며 이 명령은 웹 서버를 시작하지 않습니다. H2 파일 잠금 때문에 실행 중인 별도 서버와 동시에 복구할 수 없습니다.

```sh
java -jar app/build/libs/app-0.1.0.jar --recover-admin --data=/data
```

콘솔에서 새 비밀번호(8자 이상, 영문자·숫자·출력 가능한 ASCII 특수문자 포함, UTF-8 72바이트 이하, 대소문자 각각 필수 아님)를 숨김 입력하고 한 번 더 확인 입력합니다(불일치 시 실패). 표준 입력 줄 끝의 CR(CRLF)은 제거합니다. 콘솔이 없는 환경에서는 보호된 표준 입력으로 한 줄을 전달할 수 있습니다. 비밀번호를 명령 인자·환경변수·셸 기록에 넣지 마세요. 기존 관리자 이름은 유지하고 비밀번호를 재설정하며 TOTP·복구 코드를 해제하고 `admin-recovery` 감사를 남긴 뒤 종료합니다. 기존 데이터 폴더/관리자가 없거나 DB가 잠겼으면 실패합니다. 정상 종료 후 앱을 다시 시작하고 로그인하여 TOTP를 새로 등록하세요. 세션은 메모리에 있으므로 재시작으로 기존 세션이 사라집니다. API 토큰은 유지되므로 침해가 의심되면 별도로 폐기하세요. 오프라인 복구는 초기화나 소스 원본 변경을 하지 않습니다.

구현·검증 기록: [TOTP 검증](docs/verification/totp.md).

### MCP OAuth

기존 API 토큰과 함께 OAuth 인가 코드 + PKCE(S256)를 지원합니다. 기본 off이며 `nasquire.oauth.enabled=true`와 `nasquire.public-url=https://nasquire.example.com`을 설정하세요. HTTPS가 필수이며 루프백 HTTP만 개발용으로 허용합니다. Claude·ChatGPT 등의 원격 MCP 연결 URL에 `https://nasquire.example.com/mcp`를 입력한 뒤 관리자 로그인(TOTP 활성 시 추가 확인)과 소스별 승인을 진행합니다. OAuth 토큰은 읽기 전용 `/mcp` 전용이고 REST에는 기존 API 토큰이 필요합니다. 관리 화면 **API 토큰 → MCP OAuth**에서 승인·클라이언트를 철회할 수 있습니다.

상세 설정·제한·연결 예는 [OAuth 설치 안내](docs/install/oauth.md), 검증 범위는 [OAuth 검증](docs/verification/oauth.md)을 참고하세요.

### 색인 검사 설정

NAS HDD 절전과 대용량 폴더 검사 부담을 줄이기 위해 기본 변경 검사 주기는 5분입니다(안정화 대기 2초 유지). 환경설정 → 색인에서 검사 주기 1~60분과 파일 크기 상한 1~512 MiB(기본 32 MiB)를 저장합니다. 데이터 폴더 `indexing.properties`의 화면 설정이 `nasquire.scan-millis`·`nasquire.max-file-bytes` 환경변수/설정 초기값보다 우선하며 저장 즉시 단일 작업자의 스케줄을 재설정합니다. Office/HWP 원본은 별도 8 MiB 상한을 유지합니다.

소스별 **지금 검사**는 변경분만 검사하며 실행 중 요청은 다음 차례 한 번으로 합칩니다. 새 파일은 안정화 대기 후 다음 검사에서 색인됩니다. 크기 초과는 오류와 별도로 집계하고 관리자에게 최근 상대 경로 최대 5건을 메모리에서만 표시합니다. 상한을 올린 파일은 다음 검사부터 다시 시도합니다. 포함 glob을 비우면 현재 빌드의 지원 형식 전체를 처리합니다(`GET /api/supported-extensions`, 관리자 전용). full의 이미지 형식은 OCR을 켜야 추출됩니다. NAS 시스템 파일·폴더는 자동 제외됩니다.

## 사용자별 접근 범위 / Per-user access scope

Nasquire의 로컬 계정은 ADMIN(관리자)과 USER(사용자)로 구분합니다. 최초 설정과 기존 단일 관리자 DB는 첫 ADMIN 계정으로 이어집니다. ADMIN은 모든 소스를 검색하고 사용자·소스·업로드·공유·색인·AI/OAuth 설정을 관리합니다. USER는 검색·자기 API 토큰·계정/2단계 인증·정보·로그아웃만 사용할 수 있습니다. 관리자가 **사용자** 화면에서 허용 소스를 지정하며, USER의 기본 허용 집합은 비어 있습니다(접근 불가).

검색·문서·다운로드·MCP는 소스 권한을 검사합니다. API 토큰과 MCP OAuth 승인은 발급/승인한 계정에 속하며, 실효 범위는 지정 범위와 소유자의 현재 허용 소스의 교집합입니다. 기존 API 토큰의 빈 범위는 소유자에게 허용된 전체 소스라는 의미를 유지합니다. OAuth 승인은 명시적으로 고른 소스만 허용합니다. 권한 축소·비활성·삭제는 다음 토큰 요청과 이미 발급한 다운로드 주소에도 반영됩니다. 계정 역할·권한·상태 변경은 기존 로그인 세션을 다음 요청에 무효화합니다. 마지막 활성 ADMIN은 삭제·강등·비활성화할 수 없습니다.

강등·삭제된 관리자가 만든 공유는 유지되며 관리자 공유 화면에서 철회합니다. 세션으로 발급한 다운로드 티켓은 세션 무효화 후에도 TTL 동안 유효하지만, 수신 시 계정 활성 상태와 현재 소스 권한을 다시 검사합니다.

오프라인 복구는 앱을 종료한 뒤 `--recover-admin --data=/data --username=admin`으로 지정합니다. 관리자 계정이 하나면 사용자명을 생략할 수 있으며 여럿이면 목록을 확인한 뒤 지정해야 합니다. 비활성 ADMIN도 복구하면 활성화됩니다. 비밀번호는 기존처럼 표준 입력으로만 전달합니다. [검증 기록](docs/verification/accounts.md).

컨테이너는 단일 UID로 마운트한 폴더를 읽으며 NAS 계정별 공유 폴더 ACL을 컨테이너 안에서 적용하지 않습니다. 호스트에서는 실행 UID의 파일 권한이 적용됩니다. 폴더를 별도 소스로 분리해 Nasquire의 계정·토큰·승인 범위를 지정하세요. 선택 [OIDC 외부 로그인](docs/install/oidc.md)을 제공하며 NAS 파일 ACL 자체는 연동하지 않습니다.

Local accounts have ADMIN and USER roles. Initial setup creates the first ADMIN; an existing single administrator keeps its password, TOTP, recovery codes and session generation during migration. ADMIN can access every source and manage users, sources, uploads, shares, indexing and AI/OAuth settings. USER can access search, their own API tokens, account/two-factor settings, about and sign-out. Administrators assign source access in **Users**. A new USER starts with no source access.

Search, documents, downloads and MCP enforce source permissions. Each API token and MCP OAuth approval belongs to its issuing/approving account. Its effective scope is the intersection of its scope and the owner's current permitted sources. An empty API token scope retains its existing meaning: all sources permitted to its owner. OAuth approvals require explicitly selected sources. Permission reductions, disabled accounts and deleted accounts apply on the next token request and when an existing download URL is received. Role, scope and status changes invalidate existing login sessions on their next request. The last active ADMIN cannot be deleted, demoted or disabled.

Shares created by demoted or deleted administrators remain available until revoked in the administrator Shares screen. Session download tickets remain valid for their TTL even after session invalidation; receipt rechecks account activity and current source permissions.

Stop the application before offline recovery: `--recover-admin --data=/data --username=admin`. The username may be omitted when there is exactly one ADMIN; otherwise the command lists administrators and requires selection. Recovery also enables a disabled ADMIN. Password input remains on standard input. See [verification](docs/verification/accounts.md).

The container reads mounted folders as a single UID; it does not apply each NAS account's shared-folder ACL inside the container. Host file permissions apply to that UID. Separate folders into sources and assign account, token and approval scopes in Nasquire. Optional [OIDC sign in](docs/install/oidc.md) is available; NAS filesystem ACL integration remains future work.

## 기여 / Contributing

버그 보고·기능 제안은 이슈로 환영합니다. 외부 PR·패치 및 이슈에 첨부된 코드·문서·번역은 받지 않으며 반영하지 않습니다. 제안은 아이디어로만 참고하고 메인테이너가 직접 구현합니다. [기여 정책](CONTRIBUTING.md)과 비공개 취약점 보고를 위한 [보안 정책](SECURITY.md)을 확인하세요.

Bug reports and feature ideas are welcome through issues. External PRs, patches, and code, documentation or translations attached to issues are neither accepted nor incorporated. Suggestions are ideas only; the maintainer implements changes independently. See [Contributing](CONTRIBUTING.md) and the [Security policy](SECURITY.md) for private vulnerability reporting.
