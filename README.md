# Nasquire

**Ask your NAS.** Search existing folders on a NAS or server through a browser, REST API, or MCP client. Nasquire reads original files and keeps metadata and its keyword index in a separate data directory.

Copyright (c) 2026 Bluesounds. [Source repository](https://github.com/Nasquire/nasquire).

The keyword-search MVP supports PDF (page-level text), UTF-8 and detected-encoding text, and Markdown. Korean Nori analysis also handles English terms; Lucene ranks chunks with BM25. Embeddings, OCR, reranking, Office formats, and TOTP are planned.

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

Container image building and a strict 512 MiB container test have not yet been verified. See [verification](docs/verification/mvp.md).

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
| `nasquire.max-text-characters` | `2000000` | Extracted-character ceiling, 1200–4000000; oversized output is rejected |
| `nasquire.mcp-allowed-origins` | empty | Comma-separated exact allowed browser Origins for MCP; no Origin header is required for native clients |
| `server.servlet.session.cookie.secure` | `false` | Set `true` when serving through HTTPS |

Include/exclude globs use paths relative to the source, one per line in the UI. An empty include list includes all supported formats. `**/*.pdf` includes root-level PDFs too. Default exclusions always apply: `@eaDir`, `#recycle`, `#snapshot`, `.DS_Store`, `Thumbs.db`, `~$*`, `.~*`, backup `*~`, and `.tmp`, `.temp`, `.part`, `.swp`, `.swo`, `.crdownload` files. Directory symlinks are skipped; file symlinks are read only when their resolved target stays inside the source. Returned relative names use NFC normalization. Source and data directories must not overlap.

One low-priority worker scans and indexes sequentially. Size/mtime changes and deletions are reflected on later scans. Changes preserving both values require **Reindex**. Failed extraction is reported in source status and retried. A restart rebuilds source indexes after stability checks. Text is split into 1200-character chunks without overlap; PDF page numbers start at 1. Scanned PDFs need future OCR support. Search returns chunk text without HTML highlighting.

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
| GET | `/api/document?path=...` | Read indexed text using a search result's source-qualified path |

Administrator mutations, including setup, login, and logout, require the CSRF header from `/api/auth` and the same session cookie. Login rotates the session and CSRF token; fetch `/api/auth` again afterward. Document requests accept `page_from`/`page_to` **or** `offset`/`length` (zero-based character offset, at most 100000 characters). Responses include `truncated` and `totalCharacters`. The path format is `<source-id>:<relative-name>`; arbitrary filesystem reads are never exposed. Source removal makes its results inaccessible immediately, then the worker removes its index entries.

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

- `search_documents(query, source?, limit?)`: chunk text, source ID/name, source-qualified path, page, offset, mtime, and score.
- `get_document(path, page_from?, page_to?, offset?, length?)`: indexed text; page and character ranges are mutually exclusive.

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

The runtime check generates 40 original sample documents, starts `:app:bootRun`, exercises setup → indexing → Korean/English search → tokens → both MCP tools, records JVM RSS, and stops the server. It writes ignored artifacts under `build/verification`. [Recorded results and remaining checks](docs/verification/mvp.md).

Use HTTPS and restrict initial setup/administrator access to trusted users. Secure session cookies should be enabled behind HTTPS. This MVP has one administrator, no password recovery UI, TOTP, login rate limiting, or OAuth. A sampled RSS measurement on small documents is not a guarantee for large PDFs or concurrent workloads; test your own corpus under a real container limit. The source mount must be read-only to enforce isolation against filesystem races.

## License

[GNU AGPL v3.0](LICENSE) with [additional terms](LICENSE-EXCEPTIONS.md):

- Personal and in-company use and modification: free, no obligation to publish source.
- Free, non-commercial distribution or hosting: also exempt from publishing source.
- Selling it, or offering it as part of a paid product, subscription, or ad-gated service: AGPL applies — publish your source.
- Always keep the attribution to Nasquire.

[Third-party dependency licenses](THIRD-PARTY.md). The original license files and notices are preserved in dependency JARs.
