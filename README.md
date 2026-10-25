# Nasquire

**Ask your NAS.** Search existing folders on a NAS or server through a browser, REST API, or MCP client. Nasquire reads ordinary sources without modifying them and keeps metadata and its search index in a separate data directory. Optional upload sources use dedicated writable folders; external shares expose only one selected file or folder.

Copyright (c) 2026 Bluesounds. [Source repository](https://github.com/Nasquire/nasquire).

The search service supports PDF (page-level text), UTF-8 and detected-encoding text, Markdown, Word (.docx/.doc), PowerPoint (.pptx/.ppt), Excel (.xlsx/.xls), and Korean HWP/HWPX. Excel uses SAX/event readers. Korean Nori analysis also handles English terms; Lucene ranks chunks with BM25. Optional local ONNX and remote embeddings add Lucene HNSW vector search and BM25/vector RRF fusion. 선택 full 모듈은 OCR·리랭커를 제공하고 관리자 TOTP도 지원합니다. See [embedding configuration](docs/embeddings.md).

## Quick start

Install a Java 25 JDK. Gradle downloads Node for the frontend; a separate Node installation is unnecessary. The first build requires Internet access.

```sh
./gradlew build
./gradlew :app:bootRun
```

Open `http://localhost:8080`, create the administrator (password: at least 12 characters, at most 72 UTF-8 bytes), and sign in. In **Sources**, register an existing readable folder, wait for two scans, then use **Search playground**. Create a read-only API token in **API tokens**; its value is displayed once. The UI selects Korean or English from the browser language and supports system, light, and dark themes.

The app uses `./data` by default. Stop the application before copying its data directory for backup; restore the entire directory together. Sources can always be reindexed. Only one application instance may use a data directory.

## 선택 full 모듈

기본 빌드는 lite이며 OCR·리랭커는 off입니다. `./gradlew -Pfull build -q` 또는 `./gradlew -Pfull :app:bootRun`으로 ONNX 임베딩·원격 리랭커·외부 Tesseract OCR 구현을 포함합니다. 기존 내장 임베딩도 이제 full 빌드가 필요합니다. 원격 임베딩은 lite에서 사용할 수 있습니다. 두 빌드의 앱 JAR 경로가 같으므로 마지막 빌드 구성을 확인하세요.

full 이미지에는 Tesseract 실행 파일과 kor+eng 데이터가 포함됩니다. JAR를 직접 실행한다면 별도로 준비해야 합니다. 관리 설정 API와 제한은 [full 검증](docs/verification/full.md)을 참고하세요. 선택 Qdrant 벡터 저장소(임베딩 설정), 로컬 ONNX 리랭커(`reranker=builtin`, full), 임베딩·리랭크·OCR만 제공하는 ML 전용 모드(`--nasquire.mode=ml`, full, Bearer 필수)를 제공합니다. 실제 OCR 비교·PaddleOCR·실제 Qdrant 서버 검증은 미완료입니다. Dockerfile은 `lite`·`full` 타깃을 제공하며 기본 타깃은 lite입니다.

## Docker 빠른 시작

Linux amd64/arm64용 이미지는 `ghcr.io/nasquire/nasquire:lite`(기본·`:latest`)와 `:full`입니다. 첫 릴리스 게시 전에는 pull이 실패할 수 있으며 이번 단계에서는 게시하지 않았습니다. arm64 컨테이너 실행·헬스체크·lite 메모리 제한 실측은 [배포 검증](docs/verification/deploy.md)에 기록했습니다. 실제 NAS·대규모 부하·Actions는 미검증입니다.

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
- [Synology Container Manager](docs/install/synology.md) · [Unraid와 XML 템플릿](docs/install/unraid.md) · [TrueNAS SCALE](docs/install/truenas.md)
- [배포·CI 검증과 미실측 항목](docs/verification/deploy.md) · [full 메모리 실측](docs/verification/full.md)

## 최소·권장 사양

64비트 Linux amd64·arm64를 지원 대상으로 합니다. 32비트 ARM은 지원하지 않습니다. 아래 메모리는 컨테이너 제한이며 호스트 OS·다른 서비스의 여유 메모리는 별도로 확보하세요.

| 구성 | 최소: CPU / 메모리 제한 / 디스크 | 권장: CPU / 메모리 제한 / 디스크 |
| --- | --- | --- |
| lite 키워드 검색 | 1코어 / 512 MiB / 이미지 + 데이터 | 2코어 / 1 GiB / 이미지 + 데이터용 SSD·여유 공간 |
| lite + 원격 임베딩 | 1코어 / 512 MiB / 이미지 + 데이터·벡터 색인 | 2코어 / 1 GiB / 데이터용 SSD·벡터 증가분 여유 |
| full OCR(내장 모델 off) | 1코어 / 1 GiB / 이미지 + 데이터·OCR 임시 공간 | 2코어 / 2 GiB / 데이터용 SSD·OCR 임시 공간 |
| full + 내장 임베딩·로컬 리랭커 | 2코어 / 1.5 GiB / 이미지 + 데이터·모델 | 4코어 / 3~4 GiB / 데이터·모델용 SSD |
| ML 전용 서버(full 이미지) | 2코어 / 2 GiB / 이미지 + 모델·OCR 임시 공간 | 4코어 / 3~4 GiB / 모델·OCR 임시 공간용 SSD |

CPU·권장 메모리는 소규모 운영을 위한 시작 기준이며 처리량 보장값은 아닙니다. lite는 arm64 컨테이너 512/384/320 MiB 제한에서 스모크를 통과했지만, **최소 지원 기준은 512 MiB**입니다. 384/320 MiB는 소규모 시험 결과로 보장하지 않습니다. 원격 임베딩의 공급자 비용·서버 자원은 별도입니다.

arm64 이미지 크기는 lite 약 638 MB, full 약 899 MB(운영자 측정)입니다. 디스크는 이미지 크기에 `/data`의 DB·색인·로그·백업 여유와 선택 업로드/휴지통을 더하고, 모델을 쓰면 실제 모델 파일 크기와 다운로드 임시 공간을 더합니다. 색인은 원본 파일 개수/바이트만으로 산정할 수 없고 추출 텍스트 양·벡터 수에 비례합니다. OCR은 작업당 PNG 합계 상한 256 MiB 외에 원본 복사·PDF scratch 공간이 필요합니다.

내장 E5 검색 서버의 기존 최대 RSS는 855.7 MiB이며 로컬 리랭커/ML 서버도 512 MiB를 초과했습니다. ML 두 모델 실측은 1047.8 MiB입니다. full OCR의 1 GiB는 PNG 1건 성공을 근거로 한 시작 기준이며 **PDF OCR 실패는 아직 해결 확인 전**입니다. 1.5 GiB/2 GiB도 모델·OCR 동시 부하를 보장하지 않습니다. 문서 수 증가에 따른 메모리 영향·대규모 자료는 미실측입니다.

실측 범위와 소규모 디스크 예시는 [배포 검증](docs/verification/deploy.md)을 참고하세요.

## Configuration

Pass Spring Boot arguments to `bootRun` or the executable JAR:

```sh
./gradlew :app:bootRun --args='--server.port=8101 --nasquire.data=./data --nasquire.scan-millis=5000'
java --enable-native-access=ALL-UNNAMED -Xmx256m -XX:+UseSerialGC -jar app/build/libs/app-0.1.0.jar --nasquire.data=./data
```

| Property | Default | Purpose |
| --- | --- | --- |
| `nasquire.data` | `./data` (`/data` in Docker) | H2 metadata and Lucene index; environment variable `NASQUIRE_DATA` |
| `server.port` | `8080` | HTTP port; environment variable `PORT` |
| `nasquire.scan-millis` | `10000` | Scan interval, at least 100 ms |
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

One low-priority worker scans and indexes sequentially. Size/mtime changes and deletions are reflected on later scans. Changes preserving both values require **Reindex**. Failed extraction is reported in source status and retried. A restart rebuilds source indexes after stability checks. Text is split into 1200-character chunks without overlap; PDF page numbers start at 1. The `location` field records PDF `page`, PowerPoint `slide`, Excel `sheet`/`rowFrom`/`rowTo`, and Word/HWP `paragraph`/optional `heading`. Existing `page` is a compatibility field (extraction order for new formats); use `location` for citations. Paragraph locations do not imply printed pages. Scanned PDFs require full OCR; the reported arm64 image-only PDF failure remains under investigation. Search returns chunk text without HTML highlighting.

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

안전한 폴더 생성·덮어쓰기 없는 원자적 이동은 Java 25 FFM과 Linux libc `openat`/`mkdirat`/`renameat2`를 사용합니다. Linux amd64/arm64와 SecureDirectoryStream 지원 파일시스템이 필요합니다. 미지원 시 실패하며 경로 기반의 불안전한 쓰기로 대체하지 않습니다. bootRun·컨테이너에는 native access 옵션이 포함되어 있습니다. arm64 실기와 실제 NAS 파일시스템은 아직 검증하지 않았습니다.

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

**보안 / Security** 화면에서 선택적으로 TOTP를 켭니다(기본 꺼짐). 등록에는 현재 비밀번호 재확인이 필요하며, 등록 시 한 번 표시되는 Base32 비밀 또는 `otpauth://` URI를 인증 앱에 입력하고, 10분 안에 코드 한 번을 확인해야 활성화됩니다. QR 생성 의존성은 추가하지 않았습니다. SHA-1·6자리·30초, 서버 시각 기준 ±1단계를 허용하며 이미 사용한 단계 이하의 코드는 거부합니다. 서버와 인증 기기의 시간을 동기화하세요. 비밀은 데이터 폴더의 기존 `embedding.key`를 사용하는 AES-256-GCM으로 DB에 암호화되며 조회 API로 다시 반환하지 않습니다. 데이터 폴더와 키를 함께 보호·백업하세요. 키 파일이 없거나 바뀌었는데 암호문이 남아 있으면 새 키를 만들지 않고 오류를 기록합니다. 이 경우 키를 백업에서 복원하거나 아래 오프라인 복구로 TOTP를 해제한 뒤 임베딩 원격 키를 다시 저장하세요(복구 코드 로그인은 키 없이도 가능합니다).

활성화 때만 표시되는 일회용 복구 코드 10개를 별도 안전한 곳에 보관하세요(DB에는 SHA-256만 저장). 비밀번호 로그인 뒤 인증 앱 코드 또는 복구 코드를 입력해야 관리 권한을 받습니다. 대기 세션은 5분 후 만료되며 코드 실패도 기존 IP/계정별 지연·잠금과 감사에 포함됩니다. 같은 대기 세션에서 5회 실패하면 비밀번호 단계부터 다시 시작해야 하며, 코드 단계의 **취소**로도 비밀번호 단계로 돌아갈 수 있습니다. 복구 코드는 대소문자·공백·하이픈을 무시합니다. 등록·해제 중 실패는 로그인과 별도로 제한되어 관리자 로그인을 잠그지 않습니다. 해제에는 **현재 비밀번호와 아직 사용하지 않은 인증 앱 코드**가 모두 필요하며 복구 코드는 해제에 사용할 수 없습니다. 해제하면 남은 복구 코드도 폐기됩니다. 기존에 완료된 다른 관리자 세션은 유지되므로 분실·침해가 의심되면 오프라인 복구를 사용하세요. API Bearer 토큰·MCP 인증은 별도로 유지됩니다.

비밀번호 또는 TOTP를 잃었을 때는 데이터 폴더에 접근할 수 있는 운영자가 **앱을 종료한 상태**에서 같은 버전의 실행 JAR로 아래 명령을 실행합니다. 네트워크 복구 API는 없으며 이 명령은 웹 서버를 시작하지 않습니다. H2 파일 잠금 때문에 실행 중인 별도 서버와 동시에 복구할 수 없습니다.

```sh
java -jar app/build/libs/app-0.1.0.jar --recover-admin --data=/data
```

콘솔에서 새 비밀번호(12자 이상, UTF-8 72바이트 이하)를 숨김 입력하고 한 번 더 확인 입력합니다(불일치 시 실패). 표준 입력 줄 끝의 CR(CRLF)은 제거합니다. 콘솔이 없는 환경에서는 보호된 표준 입력으로 한 줄을 전달할 수 있습니다. 비밀번호를 명령 인자·환경변수·셸 기록에 넣지 마세요. 기존 관리자 이름은 유지하고 비밀번호를 재설정하며 TOTP·복구 코드를 해제하고 `admin-recovery` 감사를 남긴 뒤 종료합니다. 기존 데이터 폴더/관리자가 없거나 DB가 잠겼으면 실패합니다. 정상 종료 후 앱을 다시 시작하고 로그인하여 TOTP를 새로 등록하세요. 세션은 메모리에 있으므로 재시작으로 기존 세션이 사라집니다. API 토큰은 유지되므로 침해가 의심되면 별도로 폐기하세요. 오프라인 복구는 초기화나 소스 원본 변경을 하지 않습니다.

구현·검증 기록: [TOTP 검증](docs/verification/totp.md).

### MCP OAuth

기존 API 토큰과 함께 OAuth 인가 코드 + PKCE(S256)를 지원합니다. 기본 off이며 `nasquire.oauth.enabled=true`와 `nasquire.public-url=https://nasquire.example.com`을 설정하세요. HTTPS가 필수이며 루프백 HTTP만 개발용으로 허용합니다. Claude·ChatGPT 등의 원격 MCP 연결 URL에 `https://nasquire.example.com/mcp`를 입력한 뒤 관리자 로그인(TOTP 활성 시 추가 확인)과 소스별 승인을 진행합니다. OAuth 토큰은 읽기 전용 `/mcp` 전용이고 REST에는 기존 API 토큰이 필요합니다. 관리 화면 **API 토큰 → MCP OAuth**에서 승인·클라이언트를 철회할 수 있습니다.

상세 설정·제한·연결 예는 [OAuth 설치 안내](docs/install/oauth.md), 검증 범위는 [OAuth 검증](docs/verification/oauth.md)을 참고하세요.
