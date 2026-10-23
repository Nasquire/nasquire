# Nasquire

**Ask your NAS.** Nasquire turns the folders you already have on a NAS or server into a searchable, AI-ready knowledge source.

> Status: early design. Nothing to install yet.

## Idea

- Point Nasquire at existing folders (read-only). Your files are never moved or modified.
- It extracts text from documents (PDF, Office, HWP, text, …) and builds a hybrid keyword + vector index.
- AI clients (Claude, Codex, Open WebUI, …) search and read through an MCP server and HTTP API, with page-level citations.
- Runs as a single container, sized for small NAS devices, with optional external model servers for heavier workloads.

## License

To be decided before the first release.
