# W1 Spike

## Goal

Validate the technical spine before implementing business modules:

- Spring Boot 3.5 + Java 21 starts with the selected Spring AI stack.
- PostgreSQL + pgvector is managed by Flyway with `initialize-schema=false`.
- A small text set can be embedded, written to pgvector, and searched back.
- `ChatClient.stream().content()` can stream model output through SSE.

## Current Decision

The first spike uses Spring AI OpenAI-compatible clients against DashScope:

- Base URL: `https://dashscope.aliyuncs.com/compatible-mode`
- Chat model: `qwen-plus`
- Embedding model: `text-embedding-v3`

This keeps the spike focused on Spring AI API compatibility and the RAG spine. If `spring-ai-alibaba-starter-dashscope` is later required, add a second spike entry that records its exact compatible version and API differences.

## How To Run

From the project root:

```powershell
docker compose up -d postgres
$env:AI_DASHSCOPE_API_KEY="your-api-key"
.\mvnw.cmd spring-boot:run
```

The compose file maps PostgreSQL to host port `15432` to avoid conflicts with a local PostgreSQL on `5432`.

Manual checks:

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/spike/vectors/seed
Invoke-RestMethod "http://localhost:8080/api/spike/vectors/search?query=KnowSource如何保证检索安全"
Invoke-WebRequest "http://localhost:8080/api/spike/chat/stream?question=用一句话介绍KnowSource"
```

## Acceptance

- Flyway creates `vector_store` with `vector(1024)`.
- `/api/spike/vectors/seed` writes three sample chunks.
- `/api/spike/vectors/search` returns relevant chunks.
- `/api/spike/chat/stream` returns SSE token events.

## Notes

- The spike endpoint intentionally does not implement production `KnowSourceVectorStore`; it validates the baseline Spring AI `VectorStore` path first.
- Production retrieval still needs custom `KnowSourceVectorStore` to populate and query `kb_id / doc_id / status / doc_version` columns directly.
- 2026-06-22 local compile gate passed with `mvn clean test`.
- 2026-06-22 Docker + Flyway verification passed: `pgvector/pgvector:pg16` is healthy on host port `15432`; Flyway created `vector_store`, `idx_vector_embedding_hnsw`, and `idx_vector_kb_status_doc_ver`.
- 2026-06-22 full `/api/spike` endpoint verification passed with a valid `AI_DASHSCOPE_API_KEY`:
  - `POST /api/spike/vectors/seed` returned `inserted=3`, `kbId=kb-spike`, `docId=doc-spike-handbook`, `docVersion=1`.
  - `GET /api/spike/vectors/search?query=KnowSource如何保证检索安全` returned 3 relevant chunks after clearing previous spike duplicates.
  - `GET /api/spike/chat/stream?question=用一句话介绍KnowSource` returned SSE `event:token` events; `curl.exe` was used because PowerShell `Invoke-WebRequest` threw a client-side null-reference error while reading the stream.
  - Verification ran from the packaged jar: `java -jar target/KnowSource-0.0.1-SNAPSHOT.jar`.
