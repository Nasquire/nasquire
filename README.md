# Nasquire

**Ask your NAS.** Search existing folders on a NAS or server through a browser, REST API, or MCP client. Nasquire reads original files and keeps metadata and its search index in a separate data directory.

Copyright (c) 2026 Bluesounds. [Source repository](https://github.com/Nasquire/nasquire).

The search service supports PDF (page-level text), UTF-8 and detected-encoding text, Markdown, Word (.docx/.doc), PowerPoint (.pptx/.ppt), Excel (.xlsx/.xls), and Korean HWP/HWPX. Excel uses SAX/event readers. Korean Nori analysis also handles English terms; Lucene ranks chunks with BM25. Optional local ONNX and remote embeddings add Lucene HNSW vector search and BM25/vector RRF fusion. OCR, reranking, and TOTP are planned. See [embedding configuration](docs/embeddings.md).

## Quick start

Install a Java 25 JDK. Gradle downloads Node for the frontend; a separate Node installation is unnecessary. The first build requires Internet access.

```sh
./gradlew build
./gradlew :app:bootRun
```

Open `http://localhost:8080`, create the administrator (password: at least 12 characters, at most 72 UTF-8 bytes), and sign in. In **Sources**, register an existing readable folder, wait for two scans, then use **Search playground**. Create a read-only API token in **API tokens**; its value is displayed once. The UI selects Korean or English from the browser language and supports system, light, and dark themes.

The app uses `./data` by default. Stop the application before copying its data directory for backup; restore the entire directory together. Sources can always be reindexed. Only one application instance may use a data directory.

## Docker

```sh
SOURCE_DIR=/path/to/documents PUID=1000 PGID=1000 \
  docker compose -f deploy/docker-compose.yml up --build -d
```

Register `/sources/documents` in the UI. The example mounts sources read-only, persists `/data`, limits memory to 512 MiB, and runs a JRE-only image as a non-root user. `PUID`/`PGID` configure the build-time account and runtime UID/GID. Ensure this identity can read source files and write the data volume. Changing IDs for an existing volume requires adjusting its ownership before starting. PDF scratch files use the size-limited `/tmp` mount.

The 512 MiB example targets keyword/remote operation. Built-in model memory must be checked against [vector verification](docs/verification/vector.md) before enabling it. Container image building and a strict 512 MiB container test have not yet been verified. See [verification](docs/verification/mvp.md).

## Configuration

Pass Spring Boot arguments to `bootRun` or the executable JAR:

```sh
./gradlew :app:bootRun --args='--server.port=8101 --nasquire.data=./data --nasquire.scan-millis=5000'
java -Xmx256m -XX:+UseSerialGC -jar app/build/libs/app-0.1.0.jar --nasquire.data=./data
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
| `nasquire.public-url` | empty | Public base URL, e.g. `https://nas.example.com`; otherwise use the current request base |
| `nasquire.download-ttl-seconds` | `300` | Signed download lifetime, 1–86400 seconds |
| `server.servlet.session.cookie.secure` | `false` | Set `true` when serving through HTTPS |

Include/exclude globs use paths relative to the source, one per line in the UI. An empty include list includes all supported formats. `**/*.pdf` includes root-level PDFs too. Default exclusions always apply: `@eaDir`, `#recycle`, `#snapshot`, `.DS_Store`, `Thumbs.db`, `~$*`, `.~*`, backup `*~`, and `.tmp`, `.temp`, `.part`, `.swp`, `.swo`, `.crdownload` files. Directory symlinks are skipped; file symlinks are read only when their resolved target stays inside the source. Returned relative names use NFC normalization. Source and data directories must not overlap.

One low-priority worker scans and indexes sequentially. Size/mtime changes and deletions are reflected on later scans. Changes preserving both values require **Reindex**. Failed extraction is reported in source status and retried. A restart rebuilds source indexes after stability checks. Text is split into 1200-character chunks without overlap; PDF page numbers start at 1. The `location` field records PDF `page`, PowerPoint `slide`, Excel `sheet`/`rowFrom`/`rowTo`, and Word/HWP `paragraph`/optional `heading`. Existing `page` is a compatibility field (extraction order for new formats); use `location` for citations. Paragraph locations do not imply printed pages. Scanned PDFs need future OCR support. Search returns chunk text without HTML highlighting.

Office/HWP parsing additionally caps originals at 8 MiB, ZIP entries at 1024, inflated entries at 8 MiB and total inflated bytes at 16 MiB, with a minimum 1% compression ratio. XML depth is capped at 64 and nodes at 100000 per entry; HWP total expanded streams are capped at 2 MiB. Files exceeding these limits, encrypted or corrupt files, and documents with over 4096 nonempty extraction units are reported as errors. XLSX shared strings/styles and XLS SST remain in bounded memory. Formula values come from saved results; formulas are never executed. These conservative limits trade large-document coverage for the 512 MiB runtime goal.

## REST API

Authenticate reading requests with `Authorization: Bearer <token>` or an administrator session. Tokens cannot manage sources or credentials. Their SHA-256 digests are stored, and requests are audited. Empty token scope means **all current and future sources**. A restricted token can access only its selected source IDs. Expiry is mandatory; revocation takes effect on the next request.

| Method | Endpoint | Use |
| --- | --- | --- |
| GET | `/api/auth` | Setup/login state and CSRF token/header; also initializes an anonymous session |
| POST | `/api/setup` | Create the sole administrator with JSON `username`, `password` |
| POST | `/api/login` | Form-encoded `username`, `password` |
| POST | `/api/logout` | End administrator session |
| GET, POST | `/api/sources` | List/register sources (`name`, `path`, `includes`, `excludes`) |
| PUT, DELETE | `/api/sources/{id}` | Update/remove a source |
| POST | `/api/sources/{id}/reindex` | Queue a complete rebuild |
| GET | `/api/sources/{id}/status` | State, indexed/pending file counts, errors |
| GET, POST | `/api/tokens` | List/issue tokens (`name`, `expiresAt` in Unix milliseconds, `sources` ID array) |
| DELETE | `/api/tokens/{id}` | Revoke a token |
| GET | `/api/audit` | Most recent 200 events (10,000 retained) |
| GET | `/api/search?query=...&source=...&limit=20` | Search; optional source ID and limit 1–100 |
| GET | `/api/document?path=...` | Read indexed text using a search result's source-qualified path, including `locations` |
| GET | `/api/download?path=...` | Issue `{url, expiresAt}` for an original file; authenticated and source-scoped |
| GET | `/download?ticket=...` | Fetch original bytes without Bearer; requires an unexpired valid HMAC signature |

Administrator mutations, including setup, login, and logout, require the CSRF header from `/api/auth` and the same session cookie. Login rotates the session and CSRF token; fetch `/api/auth` again afterward. Document requests accept `page_from`/`page_to` **or** `offset`/`length` (zero-based character offset, at most 100000 characters). Responses include `truncated` and `totalCharacters`. The path format is `<source-id>:<relative-name>`; arbitrary filesystem reads are never exposed. Source removal makes its results inaccessible immediately, then the worker removes its index entries.

Download links expire after five minutes by default and are invalidated by server restart. Token revocation prevents new issuance; an already issued link remains usable until expiry. Source existence, include/exclude rules, real paths, and directory handles are rechecked at receipt. Downloads reject all symbolic links, including internal ones, and fail closed where secure directory streams are unavailable. Originals are opened read-only. Responses use attachment disposition with an RFC 5987 UTF-8 filename and disable caching. Links resolve the current original, not a pinned content version. Set `nasquire.public-url` behind a reverse proxy. Do not log signed query strings in proxy access logs; application audit records omit tickets and signatures. No upload or long-lived sharing feature is provided.

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

The runtime check generates 48 original sample documents (including all eight new formats), starts `:app:bootRun`, exercises setup → indexing → Korean/English search → tokens → all three MCP tools → anonymous signed-download curl, records JVM RSS, and stops the server. Set `VERIFY_EMBEDDING=builtin` to fetch pinned SHA-256-verified model files into ignored build artifacts and test local hybrid search, or `VERIFY_EMBEDDING=remote` to exercise a fake HTTP provider. Do not run builds concurrently with bootRun verification because they share class output directories. It writes ignored artifacts under `build/verification`. [Stage 3 results and limits](docs/verification/formats-download.md); [MVP results](docs/verification/mvp.md).

Use HTTPS and restrict initial setup/administrator access to trusted users. Secure session cookies should be enabled behind HTTPS. This MVP has one administrator, no password recovery UI, TOTP, login rate limiting, or OAuth. A sampled RSS measurement on small documents is not a guarantee for large PDFs or concurrent workloads; test your own corpus under a real container limit. The source mount must be read-only to enforce isolation against filesystem races.

## License

[GNU AGPL v3.0](LICENSE) with [additional terms](LICENSE-EXCEPTIONS.md):

- Personal and in-company use and modification: free, no obligation to publish source.
- Free, non-commercial distribution or hosting: also exempt from publishing source.
- Selling it, or offering it as part of a paid product, subscription, or ad-gated service: AGPL applies — publish your source.
- Always keep the attribution to Nasquire.

[Third-party dependency licenses](THIRD-PARTY.md). The original license files and notices are preserved in dependency JARs.
