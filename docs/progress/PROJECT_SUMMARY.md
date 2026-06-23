# KnowSource Project Summary

Last updated: 2026-06-22

## Project Positioning

KnowSource is an interview-oriented enterprise RAG intelligent document Q&A system. The goal is to demonstrate real AI application engineering capability, not just a chat wrapper.

Target capabilities include document ingestion, chunking, delayed vectorization, PGVector retrieval, publish/index state management, citation tracing, QaTrace, Rerank, and evaluation.

## Architecture Status

The main architecture document is:

`docs/architecture/KnowSource-RAG-架构设计文档.md`

Completed architecture corrections:

- Fixed the `doc_version IN (syncedVersions)` recall bug by requiring precise `(doc_id, doc_version)` matching.
- Clarified MVP publish semantics: publishing does not increment `documents.version`; version increments happen when uploaded/replaced files are parsed successfully.
- Simplified MVP publish consistency: no zero-downtime version switch in MVP. A document is not searchable while publishing/indexing and becomes searchable again after `index_status=SYNCED`.
- Fixed index consumer transaction boundaries: short transaction to claim outbox and mark `SYNCING`, remote embedding/vector writes outside the transaction, short transaction to mark `SYNCED` or `FAILED`.
- Added refresh token data model for the no-Redis MVP path. Only token hashes are stored.
- Aligned architecture implementation posture with the current codebase: MVP uses SQL-first `JdbcClient + Flyway`, not mandatory JPA.
- Clarified current Flyway migration order: `V1__vector_store.sql`, then `V2__business_tables.sql`.
- Clarified W2 staged ingestion: the first implementation may accept JSON `title + content`; real multipart upload, OSS, and Tika/Markdown parsing remain later W2 enhancements.

## Current Engineering State

Project root:

`KnowSource/`

The current codebase is a Spring Boot Web skeleton extended with the W1 Spike and the W2 database foundation.

Implemented stack:

- Java 21
- Spring Boot 3.5.15
- Spring AI 1.1.2
- PostgreSQL + pgvector
- Flyway
- DashScope through Spring AI OpenAI-compatible client
- Maven Wrapper

Key files:

- `pom.xml`
- `src/main/resources/application.yml`
- `src/main/resources/application-local.yml` (local only, ignored by Git; stores real API key when needed)
- `src/main/resources/application-db.yml`
- `src/main/resources/db/migration/V1__vector_store.sql`
- `src/main/resources/db/migration/V2__business_tables.sql`
- `docker-compose.yml`
- `.env.example`
- `src/main/java/com/knowsource/spike/SpikeController.java`
- `src/test/java/com/knowsource/KnowSourceApplicationTests.java`
- `docs/spike/SPIKE.md`

## W1 Spike Status

W1 Spike is complete.

Verified:

- `docker compose up -d postgres` starts `pgvector/pgvector:pg16`.
- PostgreSQL is exposed on host port `15432`.
- Flyway creates `vector_store`.
- `vector_store.embedding` is `vector(1024)`.
- HNSW vector index exists.
- B-tree index `(kb_id, status, doc_id, doc_version)` exists.
- `mvn clean test` passed.
- `.\mvnw.cmd test` passed.
- `/api/spike/vectors/seed` writes 3 sample chunks.
- `/api/spike/vectors/search` returns relevant chunks.
- `/api/spike/chat/stream` returns SSE `event:token` events.

The full W1 verification result is recorded in:

`docs/spike/SPIKE.md`

## W2 Database Foundation Status

W2 database foundation is complete.

Implemented migration:

- `src/main/resources/db/migration/V2__business_tables.sql`

Created tables:

- `users`
- `refresh_tokens`
- `knowledge_bases`
- `kb_members`
- `documents`
- `ingest_tasks`
- `chunk_parents`
- `chunk_children`
- `document_publish_events`
- `qa_traces`

Important schema choices:

- `documents` tracks `status`, `index_status`, `version`, `published_at`, and `vectors_synced_at`.
- `ingest_tasks` is limited to parsing status (`PENDING`, `PARSING`, `READY`, `FAILED` expected by design).
- `chunk_parents` and `chunk_children` both carry `doc_version`.
- `chunk_children.parent_chunk_id` references `chunk_parents.id`.
- `document_publish_events` is the Transactional Outbox table for later publish/index work.
- `qa_traces` is present for later RAG observability work, but no production QA flow uses it yet.

Verified:

- `.\mvnw.cmd test` passed after adding V2.
- `docker compose up -d postgres` confirmed PostgreSQL container is running.
- Spring Boot startup triggered Flyway and successfully migrated schema from version 1 to version 2.
- PostgreSQL table listing confirmed `vector_store` plus all V2 business tables exist.

Note:

- A full Spring Boot run without local profile/API key can still fail during `SpikeController`/Spring AI bean creation.
- The migration itself is verified; the startup failure was credential configuration related, not SQL related.

## W2 Business API Status

W2 business API foundation is complete for the current MVP scope.

Implemented:

- Demo user bootstrap:
  - `src/main/java/com/knowsource/user/DemoUserInitializer.java`
  - If `users` is empty on startup, insert one `demo` user.
  - W2 APIs use the demo user as the fixed actor until authentication/RBAC is implemented.
- Knowledge base APIs:
  - `POST /api/kbs`
  - `GET /api/kbs`
  - Creating a knowledge base also inserts an `OWNER` row into `kb_members`.
- Minimum document ingestion APIs:
  - `POST /api/kbs/{kbId}/documents`
  - `GET /api/kbs/{kbId}/documents`
  - `GET /api/documents/{docId}`
  - `GET /api/documents/{docId}/chunks`
  - The first iteration accepts JSON `title + content`, treats `content` as already extracted text, and does not use multipart upload yet.
  - Documents are created as `status=DRAFT`, `index_status=NONE`, `version=1`, `file_type=TEXT`.
  - `oss_key` uses an `inline://{docId}` placeholder until real object storage/upload is implemented.
  - Ingest tasks are marked `PARSING` first, then `READY` after chunks are persisted, or `FAILED` if parsing/persistence fails.
  - Ingest transaction boundaries were adjusted so a failed parse/persist step can leave an `ingest_tasks.status=FAILED` record instead of being rolled back with the whole request transaction.
  - Text is split by `SimpleTextChunker` into parent chunks of about 1200 characters and child chunks of about 400 characters.

Key files:

- `src/main/java/com/knowsource/user/DemoUserInitializer.java`
- `src/main/java/com/knowsource/user/DemoUserService.java`
- `src/main/java/com/knowsource/kb/KnowledgeBaseController.java`
- `src/main/java/com/knowsource/kb/KnowledgeBaseService.java`
- `src/main/java/com/knowsource/kb/CreateKnowledgeBaseRequest.java`
- `src/main/java/com/knowsource/kb/KnowledgeBaseResponse.java`
- `src/main/java/com/knowsource/document/DocumentController.java`
- `src/main/java/com/knowsource/document/DocumentService.java`
- `src/main/java/com/knowsource/document/SimpleTextChunker.java`
- `src/main/java/com/knowsource/document/CreateDocumentRequest.java`
- `src/main/java/com/knowsource/document/DocumentIngestResponse.java`
- `src/main/java/com/knowsource/document/DocumentPublishResponse.java`
- `src/main/java/com/knowsource/document/DocumentResponse.java`
- `src/main/java/com/knowsource/document/DocumentChunkResponse.java`
- `src/test/java/com/knowsource/kb/KnowledgeBaseControllerTest.java`
- `src/test/java/com/knowsource/document/DocumentControllerTest.java`
- `src/test/java/com/knowsource/document/DocumentPublishControllerTest.java`

Verified:

- `docker compose ps postgres` confirmed PostgreSQL is running and healthy.
- `.\mvnw.cmd test` passed.
- Tests verify:
  - Demo user is created from an empty `users` table.
  - `POST /api/kbs` creates a knowledge base.
  - Creating a knowledge base creates an `OWNER` membership.
  - `GET /api/kbs` returns the demo user's knowledge bases.
  - Blank knowledge base names are rejected with HTTP 400.
  - `POST /api/kbs/{kbId}/documents` creates a document, ingest task, parent chunks, and child chunks.
  - Document ingest returns `ingestStatus=READY`.
  - `GET /api/kbs/{kbId}/documents` returns documents under the knowledge base.
  - `GET /api/documents/{docId}` returns document detail.
  - `GET /api/documents/{docId}/chunks` returns child chunks ordered by `chunk_index`.
  - Blank document content is rejected with HTTP 400.
  - Missing knowledge bases return HTTP 404.

## W3 Publish/Index Flow Status

W3 publish/index foundation is complete for the current MVP backend scope.

Implemented:

- Publish API:
  - `POST /api/documents/{docId}/publish`
  - Requires the current demo user to be a knowledge base member.
  - Requires the latest ingest task to be `READY`.
  - Updates the document to `status=PUBLISHED`, `index_status=PENDING`, and sets `published_at`.
  - Creates a `document_publish_events` outbox row with `event_type=PUBLISH`.
- Outbox consumer:
  - `DocumentIndexOutboxService` claims `PENDING/FAILED` events with `FOR UPDATE SKIP LOCKED`.
  - The claim transaction marks the outbox event and document as `SYNCING`.
  - Embedding and vector writes run outside the claim transaction.
  - Success marks the event `DONE`, sets `processed_at`, and updates the document to `index_status=SYNCED`.
  - Failure marks the event/document failed, records `error_message`, increments `attempt_count`, and sets `next_retry_at`.
- Background polling:
  - `DocumentIndexEventPoller` scans pending events on a fixed delay.
  - `DocumentService.publish(...)` also attempts immediate processing after creating the outbox event.
- Vector indexing layer:
  - `VectorIndexService` reads `chunk_children` for exact `doc_id + doc_version`.
  - `SpringAiDocumentEmbeddingGateway` adapts Spring AI `EmbeddingModel`.
  - Vector rows are written to `vector_store` with `kb_id`, `doc_id`, `status='published'`, and `doc_version` populated.
  - Metadata includes `kbId`, `docId`, `docVersion`, `chunkId`, `parentChunkId`, `chunkIndex`, and `chunkType`.

Known gaps to align with the architecture:

- `DocumentService.publish(...)` currently calls `DocumentIndexOutboxService.processNextPendingEvent()` before returning. This is a W3 MVP shortcut for immediate demo/test visibility, but it does not match architecture section 6.2.1 / ADR-005, where the publish API must return `202` with `indexStatus=PENDING` immediately and leave embedding/vector writes to the asynchronous consumer.
- Because `processNextPendingEvent()` claims the next schedulable global outbox event rather than the event created by the current publish request, concurrent publishes can process each other's events. Later development should remove this synchronous call from the HTTP path, keep publish as a transaction-only operation, and let `DocumentIndexEventPoller` or a targeted async trigger process outbox events.
- Outbox failure retry currently uses a fixed `next_retry_at = now() + 30s` delay. This is another W3 MVP simplification; the target architecture expects backoff based on `attempt_count`, with exponential delay and an upper bound.
- Executor isolation is only partially implemented. `KnowSourceApplication` now enables `@Async` and QaTrace writes use a dedicated `traceExecutor`, but `ChatService.stream(...)` still uses Spring Boot's global `applicationTaskExecutor`; there are no dedicated `ingestExecutor`, `indexExecutor`, or `chatExecutor` beans yet.
- Document ingestion is synchronous in the current W2/W3 implementation: `DocumentService.ingest(...)` performs text splitting and chunk persistence on the HTTP request thread and returns `READY`. The architecture target is asynchronous ingestion through an isolated `ingestExecutor`, so later work should add `@EnableAsync`, define dedicated executor beans, and move parsing/chunking off the Tomcat request thread.
- Because streaming Q&A currently shares the global task executor, high-concurrency SSE generation can occupy shared async capacity. Later work should route SSE work to `chatExecutor` and keep ingestion, indexing, chat, and trace writing isolated as described in architecture section 11.6.
- `VectorIndexService.reindexPublishedDocument(...)` currently sends all document chunks to the embedding gateway in one call. This is a W3 MVP shortcut for small demo documents and does not implement the architecture's batching strategy. Real documents with many chunks can exceed DashScope `text-embedding-v3` per-request limits, fail deterministically, and then exhaust outbox retries without making progress. Later work should batch embedding requests by provider limits and token budget, preserve chunk order when merging results, and only mark the event failed for genuinely unrecoverable errors.
- Index polling is not isolated on `indexExecutor` yet. `DocumentIndexEventPoller` runs scheduled polling synchronously and can process up to five events on the scheduler thread, while the architecture expects scheduled triggering plus async index work on an isolated `indexExecutor`.
- DashScope calls do not yet have the architecture's Resilience4j protection. Chat, embedding, and future rerank calls should be wrapped with the documented rate limiter, timeout, retry, and fallback policies so provider slowness or throttling does not cascade through request and worker threads.
- Query and document embedding are not distinguished yet. Both indexing and retrieval call the same `DocumentEmbeddingGateway.embed(...)` path without passing DashScope `text-type=document` for indexing or `text-type=query` for retrieval, which can reduce retrieval quality compared with the architecture target.
- The stale indexing recovery timeout differs from the architecture. Current config defaults `knowsource.index.syncing-timeout-seconds` to 300 seconds, while the architecture describes `SYNCING > 30min` recovery. Later work should align the default or explicitly document the shorter MVP timeout.
- Outbox terminal status naming differs from the architecture. The current implementation marks successful events as `DONE`, while the architecture tables and examples use `PROCESSED`. Later work should align the schema/code/docs vocabulary before adding operational tooling.
- Outbox retry controls are still hard-coded and have no management retry API. `MAX_ATTEMPTS` is fixed in code, and after attempts are exhausted there is no admin/manual requeue endpoint yet; later work should externalize retry policy and add controlled event requeue support.

Verified:

- `.\mvnw.cmd test` passed.
- Tests verify:
  - Draft documents have no vector rows before publish.
  - Publishing creates a DONE outbox event.
  - Publishing writes one vector row per child chunk.
  - Vector rows include the required `kb_id / doc_id / status / doc_version` columns.
  - Documents become `PUBLISHED` and `index_status=SYNCED` after successful indexing.
  - Publishing is rejected when the latest ingest task is not `READY`.

## W3 Production Retrieval and Q&A Status

Production retrieval, the first non-streaming Q&A endpoint, and the production SSE Q&A endpoint are complete for the current MVP backend scope.

Implemented:

- Production chat API:
  - `POST /api/kbs/{kbId}/chat`
  - `POST /api/kbs/{kbId}/chat/stream`
  - Uses the current demo user as fixed actor until authentication/RBAC is implemented.
  - Requires the current demo user to be a knowledge base member.
  - Accepts JSON `question` and optional `topK`.
  - Non-streaming returns `qaTraceId`, `answer`, `refused`, and `sources`.
  - SSE streaming emits `sources`, then one or more `token` events, then a final `done` event containing `qaTraceId`.
- Vector search/query service:
  - Embeds the user query through `DocumentEmbeddingGateway`.
  - Searches `vector_store` with pgvector cosine distance.
  - Filters server-side by `kb_id`, `vector_store.status='published'`, `documents.status='PUBLISHED'`, and `documents.index_status='SYNCED'`.
  - Joins `documents` on exact `doc_id + doc_version + kb_id`, preserving the architecture rule that retrieval must not use loose `doc_version IN (...)` matching.
  - Returns source metadata including `chunkId`, `docId`, `docVersion`, document title, chunk index, optional page number, snippet, and similarity score.
- Answer generation:
  - If a `ChatClient.Builder` bean exists, `SpringAiAnswerGenerator` calls the configured chat model with a citation-only prompt.
  - For streaming, the same generator uses `ChatClient.stream().content()` and forwards model chunks as SSE `token` events.
  - If no chat model is configured, the endpoint returns a deterministic retrieval-first placeholder answer, keeping tests and DB-only profile stable.
  - Empty retrieval context returns the explicit refusal answer: `知识库中未找到相关信息。`

- QaTrace observability:
  - Non-streaming and SSE production Q&A both create a `qa_traces` row.
  - `qaTraceId` is generated before response completion and returned to clients.
  - Trace rows record `query`, retrieved source chunks as JSONB, final `answer`, `retrieval_ms`, `llm_ms`, `generation_first_token_ms` for SSE, `total_ms`, and `rag_profile='naive'`.
  - Trace writes run through `QaTraceService.recordAsync(...)` on a dedicated `traceExecutor`.
  - Empty-context refusals are traced as first-class QA attempts.
  - `GET /api/kbs/{kbId}/qa-traces` lists recent traces for a knowledge base.
  - `GET /api/kbs/{kbId}/qa-traces/{traceId}` returns trace details, including retrieved chunks and timing fields.
  - Trace query APIs still use the current demo user and knowledge-base membership check until real JWT/RBAC lands.

Key files:

- `src/main/java/com/knowsource/chat/ChatController.java`
- `src/main/java/com/knowsource/chat/ChatService.java`
- `src/main/java/com/knowsource/chat/VectorSearchService.java`
- `src/main/java/com/knowsource/chat/SpringAiAnswerGenerator.java`
- `src/main/java/com/knowsource/chat/ChatRequest.java`
- `src/main/java/com/knowsource/chat/ChatResponse.java`
- `src/main/java/com/knowsource/chat/SourceCitation.java`
- `src/main/java/com/knowsource/chat/StreamingAnswerGenerator.java`
- `src/main/java/com/knowsource/chat/ChatStreamDone.java`
- `src/main/java/com/knowsource/chat/QaTraceController.java`
- `src/main/java/com/knowsource/chat/QaTraceRecord.java`
- `src/main/java/com/knowsource/chat/QaTraceService.java`
- `src/main/java/com/knowsource/chat/QaTraceSummaryResponse.java`
- `src/main/java/com/knowsource/chat/QaTraceDetailResponse.java`
- `src/main/java/com/knowsource/config/AsyncConfig.java`
- `src/test/java/com/knowsource/chat/ChatControllerTest.java`

Verified:

- `.\mvnw.cmd -Dtest=ChatControllerTest test` passed.
- `.\mvnw.cmd test` passed.
- Tests verify:
  - Published and `SYNCED` documents can be retrieved through the production chat endpoint.
  - Non-streaming chat returns `qaTraceId` and persists a matching `qa_traces` row.
  - Production SSE emits `sources`, `token`, and `done` events.
  - Production SSE `done` contains `qaTraceId` and persists a trace row with first-token latency.
  - `GET /api/kbs/{kbId}/qa-traces/{traceId}` returns persisted trace details.
  - `GET /api/kbs/{kbId}/qa-traces?limit=1` lists recent traces in newest-first order.
  - Invalid trace list limits are rejected with HTTP 400.
  - Missing trace ids return HTTP 404.
  - Empty-context SSE emits empty `sources`, a refusal token, and `done.refused=true`.
  - Draft documents are not retrieved.
  - Existing vector rows are ignored when the owning document is no longer `index_status=SYNCED`.
  - Blank questions are rejected with HTTP 400.
  - `topK` outside `1..15` is rejected with HTTP 400.

## Local Configuration Status

Local API key configuration now uses the Spring `local` profile.

Files/status:

- `src/main/resources/application-local.yml` contains local DashScope settings and the real API key.
- `src/main/resources/application-local.yml` is ignored in `.gitignore`.
- `src/main/resources/application.yml` no longer imports `.env`; it keeps environment-variable placeholders for shared/deployable config.
- Windows user-level `SPRING_PROFILES_ACTIVE=local` was set so new PowerShell sessions should activate the local profile automatically.

Verified:

```powershell
$env:SPRING_PROFILES_ACTIVE='local'
.\mvnw.cmd test
```

The test passed.

## Maven Wrapper Status

`mvnw.cmd` was fixed.

Root cause:

- The wrapper script accessed `(Get-Item $MAVEN_M2_PATH).Target[0]` directly.
- On a normal directory, `.Target` is null, causing `Cannot index into a null array`.

Fix:

- Guarded `.Target` access before indexing.
- Ensured the batch wrapper returns Maven's exit code after execution.

Verified:

```powershell
.\mvnw.cmd -version
.\mvnw.cmd test
```

Both commands now work.

## Current Limitations

- `SpikeController` is only a technical spike and is not production retrieval logic.
- API keys must stay outside tracked repository files. Current local setup uses ignored `application-local.yml`.
- W2 knowledge base APIs, minimum JSON-based document ingestion APIs, and W3 publish/index APIs are implemented.
- Production retrieval plus non-streaming and SSE Q&A endpoints are implemented.
- QaTrace write and query paths are implemented for production Q&A.
- Document ingestion currently accepts already-extracted text only; real file upload/object storage/parser integration is not implemented yet.
- Chunking currently uses simple character-based splitting; Markdown/semantic/recursive splitting is not implemented yet.
- Authentication/RBAC is not implemented yet.
- PowerShell `Invoke-WebRequest` threw a client-side null-reference error when reading SSE; `curl.exe --no-buffer` successfully verified the stream endpoint.

## Recommended Next Step

Move from W3 Q&A traceability into answer quality and W3/W4 architecture-debt cleanup.

Recommended order:

1. Improve answer quality:
   - Add Rerank after rough vector retrieval.
   - Add profile routing (`auto`, `naive`, later `modular`) after the streaming baseline is stable.

2. Clean W3 publish/index shortcuts:
   - Remove synchronous outbox processing from the publish HTTP path.
   - Add targeted async trigger or rely on the poller.
   - Externalize retry/backoff settings before adding operational controls.

3. Then add a simple trace/admin demo surface when frontend work starts.

Do not prioritize frontend, EvalRunner, or JWT/RBAC until the production answer loop has at least one answer-quality improvement path.
