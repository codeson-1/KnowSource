# KnowSource Project Summary

Last updated: 2026-06-24

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
  - The demo user remains a local bootstrap/admin account, but production APIs now resolve the actor from Spring Security/JWT instead of using a fixed demo actor.
- Authentication APIs:
  - `POST /api/auth/login`
  - `POST /api/auth/refresh`
  - `POST /api/auth/logout`
  - Login validates stored password hashes through Spring Security `PasswordEncoder`.
  - Access tokens are stateless HS256 JWTs.
  - Refresh tokens are random opaque tokens; only SHA-256 hashes are stored in `refresh_tokens`.
  - Refresh rotates the refresh token and revokes the old one.
- Knowledge base APIs:
  - `POST /api/kbs`
  - `GET /api/kbs`
  - Creating a knowledge base also inserts an `OWNER` row into `kb_members`.
- Minimum document ingestion APIs:
  - `POST /api/kbs/{kbId}/documents`
  - `POST /api/kbs/{kbId}/documents/upload`
  - `GET /api/kbs/{kbId}/documents`
  - `GET /api/documents/{docId}`
  - `GET /api/documents/{docId}/ingest-task`
  - `POST /api/documents/{docId}/ingest-task/retry`
  - `GET /api/documents/{docId}/chunks`
  - JSON `title + content` ingestion remains available and treats `content` as already extracted text.
  - Multipart upload is implemented for `.txt`, `.md`, `.markdown`, `.pdf`, `.doc`, and `.docx` files.
  - Uploaded source files are stored through `SourceStorageService`; `knowsource.storage.type=local` uses local durable storage, and `knowsource.storage.type=oss` uses the Aliyun OSS HTTP adapter.
  - Text files are read directly as UTF-8.
  - Markdown files are parsed into heading-aware text/table blocks before chunking.
  - PDF files are parsed page-by-page through PDFBox so chunks can carry page numbers.
  - Word files are parsed through Apache Tika before chunking.
  - Multipart documents store `oss_key=local://...` or `oss://bucket/key` depending on storage type; JSON documents continue to use `inline://{docId}`.
  - Documents are created as `status=DRAFT`, `index_status=NONE`, `version=1`, and `file_type=TEXT`, `MARKDOWN`, `PDF`, or `WORD`.
  - Ingest tasks are created as `PENDING`, then processed on the dedicated `ingestExecutor`.
  - Multipart background ingestion reads the source from durable storage instead of carrying request content in memory.
  - Background ingestion marks tasks `PARSING`, then `READY` after chunks are persisted, or `FAILED` if parsing/persistence fails.
  - Failed multipart ingest tasks can be retried from any durable uploaded source, including local or OSS-backed storage.
  - The create-document API returns immediately with `ingestStatus=PENDING`; clients can poll `GET /api/documents/{docId}/ingest-task` for status and chunk counts.
  - Ingest transaction boundaries were adjusted so a failed parse/persist step can leave an `ingest_tasks.status=FAILED` record instead of being rolled back with the document creation transaction.
  - Extracted blocks are split by `SimpleTextChunker` into parent chunks of about 1200 characters and child chunks of about 400 characters while preserving page number and chunk type metadata where available.

Key files:

- `src/main/java/com/knowsource/user/DemoUserInitializer.java`
- `src/main/java/com/knowsource/security/SecurityConfig.java`
- `src/main/java/com/knowsource/security/JwtAuthenticationFilter.java`
- `src/main/java/com/knowsource/security/JwtService.java`
- `src/main/java/com/knowsource/security/CurrentUserService.java`
- `src/main/java/com/knowsource/auth/AuthController.java`
- `src/main/java/com/knowsource/auth/AuthService.java`
- `src/main/java/com/knowsource/kb/KnowledgeBaseController.java`
- `src/main/java/com/knowsource/kb/KnowledgeBaseService.java`
- `src/main/java/com/knowsource/kb/CreateKnowledgeBaseRequest.java`
- `src/main/java/com/knowsource/kb/KnowledgeBaseResponse.java`
- `src/main/java/com/knowsource/document/DocumentController.java`
- `src/main/java/com/knowsource/document/DocumentService.java`
- `src/main/java/com/knowsource/document/SimpleTextChunker.java`
- `src/main/java/com/knowsource/document/ExtractedDocument.java`
- `src/main/java/com/knowsource/document/ExtractedBlock.java`
- `src/main/java/com/knowsource/document/MarkdownStructureParser.java`
- `src/main/java/com/knowsource/document/SourceStorageService.java`
- `src/main/java/com/knowsource/document/LocalSourceStorageService.java`
- `src/main/java/com/knowsource/document/OssSourceStorageService.java`
- `src/test/java/com/knowsource/document/OssSourceStorageSmokeTest.java`
- `docs/progress/OSS_SMOKE_TEST.md`
- `src/main/java/com/knowsource/document/DocumentTextExtractor.java`
- `src/main/java/com/knowsource/document/PlainTextDocumentTextExtractor.java`
- `src/main/java/com/knowsource/document/StoredSource.java`
- `src/main/java/com/knowsource/document/CreateDocumentRequest.java`
- `src/main/java/com/knowsource/document/DocumentIngestResponse.java`
- `src/main/java/com/knowsource/document/DocumentPublishResponse.java`
- `src/main/java/com/knowsource/document/DocumentResponse.java`
- `src/main/java/com/knowsource/document/DocumentChunkResponse.java`
- `src/test/java/com/knowsource/kb/KnowledgeBaseControllerTest.java`
- `src/test/java/com/knowsource/auth/AuthSecurityTest.java`
- `src/test/java/com/knowsource/document/DocumentControllerTest.java`
- `src/test/java/com/knowsource/document/DocumentPublishControllerTest.java`

Verified:

- `docker compose ps postgres` confirmed PostgreSQL is running and healthy.
- `.\mvnw.cmd test` passed.
- Tests verify:
  - Demo user is created from an empty `users` table.
  - Login returns access and refresh tokens for the demo user.
  - Refresh token hashes are stored instead of raw refresh tokens.
  - Refresh token rotation revokes the old refresh token.
  - Logout revokes the supplied refresh token.
  - Protected APIs reject unauthenticated requests with HTTP 401.
  - VIEWER members can list/read their knowledge bases but cannot upload/write documents.
  - `POST /api/kbs` creates a knowledge base.
  - Creating a knowledge base creates an `OWNER` membership.
  - `GET /api/kbs` returns the demo user's knowledge bases.
  - Blank knowledge base names are rejected with HTTP 400.
  - `POST /api/kbs/{kbId}/documents` creates a document and pending ingest task without doing parsing/chunking on the request thread.
  - `POST /api/kbs/{kbId}/documents/upload` stores a text/Markdown source file before returning, then ingests it asynchronously from storage.
  - Document ingest returns `ingestStatus=PENDING`.
  - `GET /api/documents/{docId}/ingest-task` returns `READY` plus parent/child chunk counts after background parsing completes.
  - `GET /api/kbs/{kbId}/documents` returns documents under the knowledge base.
  - `GET /api/documents/{docId}` returns document detail.
  - `GET /api/documents/{docId}/chunks` returns child chunks ordered by `chunk_index`.
  - Blank document content is rejected with HTTP 400.
  - Markdown table blocks are persisted with `chunk_type=TABLE` and heading context.
  - PDF upload is parsed page-by-page and persisted with `page_number` metadata when extractable text exists.
  - Corrupt or empty-extraction PDF files keep their document record and mark the ingest task `FAILED`.
  - A failed multipart ingest task can be retried from the stored source and rebuild chunks after the source is fixed.
  - Retrying non-FAILED ingest tasks is rejected with HTTP 400.
  - Unsupported multipart file types are rejected with HTTP 400.
  - OSS smoke test stores, reads, and deletes a real OSS object when `KNOWSOURCE_OSS_SMOKE_ENABLED=true`; it skips by default when credentials are absent.
  - Missing knowledge bases return HTTP 404.

## W3 Publish/Index Flow Status

W3 publish/index foundation is complete for the current MVP backend scope.

Implemented:

- Publish API:
  - `POST /api/documents/{docId}/publish`
  - `POST /api/documents/{docId}/index-events/{eventId}/requeue`
  - Requires the current JWT user to be a knowledge base member with write access (`OWNER`, `EDITOR`, or global `ADMIN`).
  - Requires the latest ingest task to be `READY`.
  - Updates the document to `status=PUBLISHED`, `index_status=PENDING`, and sets `published_at`.
  - Creates a `document_publish_events` outbox row with `event_type=PUBLISH`.
- Outbox consumer:
  - `DocumentIndexOutboxService` claims `PENDING/FAILED` events with `FOR UPDATE SKIP LOCKED`.
  - The claim transaction marks the outbox event and document as `SYNCING`.
  - Embedding and vector writes run outside the claim transaction.
  - Success marks the event `DONE`, sets `processed_at`, and updates the document to `index_status=SYNCED`.
  - Failure marks the event/document failed, records `error_message`, increments `attempt_count`, and sets `next_retry_at` using configurable exponential backoff.
  - Retry policy is configured by `knowsource.index.retry.max-attempts`, `initial-delay-seconds`, and `max-delay-seconds`.
  - Failed index events can be manually requeued to `PENDING`.
- Background polling:
  - `DocumentIndexEventPoller` scans pending events on a fixed delay.
  - Scheduled polling only triggers work; outbox processing runs on the isolated `indexExecutor`.
  - A local `AtomicBoolean` drain guard prevents overlapping index drains in the same application instance.
  - The poller is controlled by `knowsource.index.poller.enabled` and is enabled by default.
  - The `db` test profile disables the poller so tests can assert publish/outbox states deterministically.
- Executor isolation:
  - `AsyncConfig` defines dedicated `ingestExecutor`, `indexExecutor`, `chatExecutor`, and `traceExecutor` beans.
  - Production SSE generation runs on `chatExecutor` instead of Spring Boot's global `applicationTaskExecutor`.
  - QaTrace writes continue to run on the dedicated `traceExecutor`.
- Provider resilience:
  - `AiProviderResilience` wraps Spring AI chat and embedding calls with Resilience4j rate limiter and bulkhead controls.
  - Embedding calls also use configurable Resilience4j retry before failures bubble to the caller.
  - Index-time embedding failures still bubble into `DocumentIndexOutboxService`, which marks the event/document failed and schedules outbox retry.
  - Query embedding failures degrade retrieval to empty context, producing a normal refusal answer and QaTrace instead of a 500.
  - Chat generation failures degrade to a stable "AI busy" answer while preserving retrieved sources and QaTrace.
  - `AiHttpClientConfig` configures Spring AI's underlying `RestClient.Builder` and `WebClient.Builder` with hard connect/read timeouts for OpenAI-compatible DashScope calls.
- Vector indexing layer:
  - `VectorIndexService` reads `chunk_children` for exact `doc_id + doc_version`.
  - `SpringAiDocumentEmbeddingGateway` adapts Spring AI `EmbeddingModel`.
  - Embedding requests are batched by `knowsource.index.embedding-batch-size` before vector rows are written.
  - Vector rows are written to `vector_store` with `kb_id`, `doc_id`, `status='published'`, and `doc_version` populated.
  - Metadata includes `kbId`, `docId`, `docVersion`, `chunkId`, `parentChunkId`, `chunkIndex`, `pageNumber`, and `chunkType`.

Known gaps to align with the architecture:

- W2 JSON ingestion is asynchronous for parsing/chunk persistence, but the raw source content is still passed from the request to an in-memory background task. Restart-safe source recovery applies to multipart upload paths through local or OSS-backed durable storage.
- DashScope resilience is implemented for chat, embedding, and rerank calls.
- Direct DashScope embedding with `text_type=document/query` is implemented but disabled by default to preserve the existing Spring AI embedding path unless `knowsource.embedding.dashscope.enabled=true` is explicitly set.
- The stale indexing recovery timeout differs from the architecture. Current config defaults `knowsource.index.syncing-timeout-seconds` to 300 seconds, while the architecture describes `SYNCING > 30min` recovery. Later work should align the default or explicitly document the shorter MVP timeout.
- Outbox terminal status naming differs from the architecture. The current implementation marks successful events as `DONE`, while the architecture tables and examples use `PROCESSED`. Later work should align the schema/code/docs vocabulary before adding operational tooling.
- Outbox retry controls are configurable, and failed events can be manually requeued.
- OSS storage is implemented behind `SourceStorageService`, and an environment-backed smoke test is available. A real-bucket run still requires valid OSS credentials and network access.

Verified:

- `.\mvnw.cmd test` passed.
- Tests verify:
  - Draft documents have no vector rows before publish.
  - Publishing creates a PENDING outbox event and returns `indexStatus=PENDING` without indexing synchronously.
  - Explicit outbox processing creates a DONE outbox event.
  - Explicit outbox processing writes one vector row per child chunk.
  - Explicit outbox processing batches embedding requests using the configured batch size while preserving chunk order.
  - Outbox failures use configurable exponential retry backoff and update the owning document to `index_status=FAILED`.
  - Failed index events can be requeued and then successfully processed by the outbox consumer.
  - Requeue rejects non-FAILED index events with HTTP 400.
  - Vector rows include the required `kb_id / doc_id / status / doc_version` columns.
  - Vector row metadata includes page number and chunk type when chunk metadata is available.
  - Documents become `PUBLISHED` at publish time and `index_status=SYNCED` after successful outbox indexing.
  - Publishing is rejected when the latest ingest task is not `READY`.

## W3 Production Retrieval and Q&A Status

Production retrieval, the first non-streaming Q&A endpoint, and the production SSE Q&A endpoint are complete for the current MVP backend scope.

Implemented:

- Production chat API:
  - `POST /api/kbs/{kbId}/chat`
  - `POST /api/kbs/{kbId}/chat/stream`
  - Uses the current JWT-authenticated user as actor.
  - Requires the current user to be a knowledge base member.
  - Accepts JSON `question`, optional `topK`, optional `profile`, and optional `sessionId`.
  - Non-streaming returns `qaTraceId`, `sessionId`, original `question`, optional `rewrittenQuery`, routed `ragProfile`, `answer`, `refused`, and `sources`.
  - SSE streaming emits `sources`, then one or more `token` events, then a final `done` event containing `qaTraceId`, `sessionId`, `rewrittenQuery`, and `ragProfile`.
- Multi-turn and Modular RAG MVP:
  - `V3__chat_sessions.sql` adds `chat_sessions` and `chat_messages`.
  - First chat call creates a session when `sessionId` is absent.
  - Follow-up calls can pass `sessionId`; user and assistant messages are persisted to `chat_messages`.
  - `profile=naive` forces the single-query path.
  - `profile=modular` enables history-aware query rewriting and 2-way retrieval expansion.
  - `profile=auto` routes single-turn requests to `naive` and requests with existing session history to `modular`.
  - `QueryRewriteService` now supports provider-backed CompressionQuery when a `ChatClient.Builder` bean is available.
  - CompressionQuery failures or missing model configuration degrade to the deterministic MVP rewrite path.
  - Modular retrieval emits a primary standalone query plus at most one expansion query (`knowsource.modular-rag.multi-query.count`, capped at 2).
- Vector search/query service:
  - Embeds the user query through `DocumentEmbeddingGateway`.
  - Searches `vector_store` with pgvector cosine distance and retrieves a configurable rough candidate set (`knowsource.retrieval.candidate-top-k`, default 15).
  - Modular RAG can run multiple retrieval queries, merge candidates by `(doc_id, doc_version, chunk_id)`, keep the best score, and rerank the merged candidate set.
  - Applies a pluggable `DocumentReranker` before trimming results to the requested `topK`.
  - Current default reranker is `DashScopeDocumentReranker`, which calls DashScope `qwen3-rerank` when configured and falls back to `LexicalDocumentReranker` when the provider is unavailable, unconfigured, or fails.
  - DashScope rerank is configurable through `knowsource.rerank.provider`, `knowsource.rerank.dashscope.endpoint`, `model`, `protocol`, and `instruct`. The `db` test profile sets `provider=none` to keep tests deterministic and offline.
  - Embedding calls are split by semantic type: indexing uses `DocumentEmbeddingGateway.embedDocuments(...)`, retrieval uses `embedQuery(...)`.
  - `DashScopeEmbeddingGateway` can be enabled with `knowsource.embedding.dashscope.enabled=true` to call the DashScope compatible embeddings endpoint directly with `text_type=document` or `text_type=query`.
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
  - Trace rows record `session_id`, original `query`, `rewritten_query`, retrieved source chunks as JSONB, final `answer`, `retrieval_ms`, `rewrite_llm_ms`, `llm_ms`, `generation_first_token_ms` for SSE, `total_ms`, and the routed `rag_profile`.
  - Trace writes run through `QaTraceService.recordAsync(...)` on a dedicated `traceExecutor`.
  - Empty-context refusals are traced as first-class QA attempts.
  - `GET /api/kbs/{kbId}/qa-traces` lists recent traces for a knowledge base.
  - `GET /api/kbs/{kbId}/qa-traces/{traceId}` returns trace details, including retrieved chunks and timing fields.
  - Trace query APIs use the current JWT-authenticated user and knowledge-base membership check.

Key files:

- `src/main/java/com/knowsource/chat/ChatController.java`
- `src/main/java/com/knowsource/chat/ChatService.java`
- `src/main/java/com/knowsource/chat/VectorSearchService.java`
- `src/main/java/com/knowsource/chat/DocumentReranker.java`
- `src/main/java/com/knowsource/chat/DashScopeDocumentReranker.java`
- `src/main/java/com/knowsource/chat/DashScopeRerankClient.java`
- `src/main/java/com/knowsource/chat/LexicalDocumentReranker.java`
- `src/main/java/com/knowsource/chat/RagProfile.java`
- `src/main/java/com/knowsource/chat/RagProfileRouter.java`
- `src/main/java/com/knowsource/chat/ChatSessionService.java`
- `src/main/java/com/knowsource/chat/ChatSessionHistory.java`
- `src/main/java/com/knowsource/chat/ChatMessage.java`
- `src/main/java/com/knowsource/chat/QueryRewriteService.java`
- `src/main/java/com/knowsource/chat/QueryRewriteResult.java`
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
- `src/main/java/com/knowsource/ai/AiProviderException.java`
- `src/main/java/com/knowsource/ai/AiProviderResilience.java`
- `src/main/java/com/knowsource/config/AiHttpClientConfig.java`
- `src/main/java/com/knowsource/index/DashScopeEmbeddingGateway.java`
- `src/main/java/com/knowsource/index/DocumentEmbeddingGateway.java`
- `src/main/java/com/knowsource/config/AsyncConfig.java`
- `src/main/resources/db/migration/V3__chat_sessions.sql`
- `src/test/java/com/knowsource/ai/AiProviderResilienceTest.java`
- `src/test/java/com/knowsource/chat/ChatAnswerFallbackTest.java`
- `src/test/java/com/knowsource/chat/ChatControllerTest.java`
- `src/test/java/com/knowsource/chat/DashScopeDocumentRerankerTest.java`
- `src/test/java/com/knowsource/chat/DashScopeRerankClientTest.java`
- `src/test/java/com/knowsource/config/AiHttpClientConfigTest.java`
- `src/test/java/com/knowsource/index/DashScopeEmbeddingGatewayTest.java`

Verified:

- `.\mvnw.cmd -Dtest=ChatControllerTest test` passed.
- `.\mvnw.cmd test` passed with 65 tests, 0 failures, 0 errors, and 1 skipped OSS smoke test.
- Tests verify:
  - Chat requests accept `profile=auto`, `profile=naive`, and `profile=modular`.
  - Single-turn `profile=auto` routes to `naive`.
  - Follow-up requests with existing session history and `profile=auto` route to `modular`.
  - New chat calls create a `chat_sessions` row and persist user/assistant messages.
  - Multi-turn follow-up questions persist and return `rewrittenQuery`.
  - QaTrace details expose `sessionId`, original `query`, `rewrittenQuery`, and `ragProfile`.
  - Non-streaming chat and SSE `done` responses expose the routed `ragProfile`.
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
  - Rough vector candidates are reranked before the requested `topK` is applied.
  - DashScope rerank HTTP requests include model/query/documents/top_n, parse provider result indexes, and also support the DashScope service response shape.
  - DashScope rerank falls back to lexical rerank when provider calls fail.
  - Indexing uses `embedDocuments(...)`; retrieval uses `embedQuery(...)`.
  - Direct DashScope embedding HTTP requests include `text_type=document` for document indexing and `text_type=query` for query retrieval.
  - Query embedding provider failures degrade to empty-context refusals with QaTrace.
  - Non-streaming and SSE chat provider failures degrade to stable fallback answers while preserving sources and QaTrace.
  - Spring AI/OpenAI-compatible REST and streaming HTTP clients are built with hard connect/read timeout settings.
  - Production SSE generation runs on `chatExecutor`.
  - Index poller outbox processing runs on `indexExecutor`.
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

## Authentication and RBAC Status

JWT/RBAC is complete for the current MVP backend scope.

Implemented:

- Spring Security stateless API security.
- `/api/auth/login`, `/api/auth/refresh`, and `/api/auth/logout`.
- HS256 JWT access tokens with configurable secret and TTL.
- Refresh token persistence using SHA-256 token hashes only.
- Refresh-token rotation and logout revocation.
- Current actor resolution through `CurrentUserService` and Spring Security context.
- Replacement of fixed demo actor in knowledge base, document, chat, and QaTrace services.
- Knowledge base membership checks remain server-side.
- Document upload/ingest, publish, retry, and requeue require write access.
- VIEWER members can read/list/chat/trace but cannot write documents.

Verified:

- `.\mvnw.cmd test` passed.
- Tests verify login, refresh, refresh-token rotation, logout revocation, unauthenticated 401, and VIEWER write denial.

## Evaluation Baseline Status

EvalRunner/golden-set reporting is complete for the current backend demo path.

Implemented:

- `docs/eval/golden-set.jsonl` defines 10 baseline cases:
  - 5 single-turn in-scope retrieval/citation cases.
  - 3 multi-turn or modular retrieval cases.
  - 2 out-of-scope refusal cases.
- `src/test/java/com/knowsource/eval/EvalRunnerTest.java` seeds a deterministic evaluation knowledge base, publishes documents through the real publish/outbox path, asks questions through the production chat endpoint, and writes `docs/eval/report.md`.
- The evaluation uses a deterministic test embedding gateway so the report is stable offline and does not depend on DashScope availability.
- The generated report records total cases, in-scope/out-of-scope counts, Recall@5, citation hit rate, refusal accuracy, and per-case source titles/pass status.

Latest report:

- `docs/eval/report.md`
- Total cases: 10
- In-scope cases: 8
- Out-of-scope cases: 2
- Recall@5: 100.0%
- Citation hit rate: 100.0%
- Refusal accuracy: 100.0%

Verified:

- `.\mvnw.cmd compile` passed.
- `.\mvnw.cmd "-Dtest=QueryRewriteServiceTest" test` passed.
- `.\mvnw.cmd test-compile` passed.
- `docker compose ps postgres` confirmed `knowsource-postgres` is running and healthy.
- `Test-NetConnection localhost:15432` succeeded.
- `.\mvnw.cmd "-Dtest=ChatControllerTest,EvalRunnerTest" test` passed with 22 tests, 0 failures, 0 errors.

## Current Limitations

- `SpikeController` is only a technical spike and is not production retrieval logic.
- API keys must stay outside tracked repository files. Current local setup uses ignored `application-local.yml`.
- W2 knowledge base APIs, minimum JSON-based document ingestion APIs, and W3 publish/index APIs are implemented.
- Production retrieval plus non-streaming and SSE Q&A endpoints are implemented.
- Retrieval now has rough recall plus pluggable rerank. DashScope `qwen3-rerank` is wired as the default provider-backed path, with lexical fallback for unconfigured/offline/failure cases.
- Chat and embedding calls now have Resilience4j rate limiter/bulkhead protection; embedding has retry. Spring AI/OpenAI-compatible REST and streaming clients now have hard connect/read timeout settings.
- QaTrace write and query paths are implemented for production Q&A.
- Multi-turn chat session persistence and Modular RAG MVP routing are implemented. LLM-backed CompressionQuery is wired as an optional provider-backed path, with deterministic fallback, and MultiQuery expansion is capped at 2 queries for MVP latency control.
- Document ingestion now supports JSON text ingestion plus multipart `.txt`/Markdown/PDF/Word upload backed by pluggable local or OSS durable source storage.
- OSS integration is implemented as `OssSourceStorageService` and selected with `knowsource.storage.type=oss`; `OssSourceStorageSmokeTest` documents and automates real-bucket verification.
- Markdown parsing preserves heading context and table blocks. PDF parsing preserves page numbers. Word parsing uses Apache Tika.
- EvalRunner is implemented as a deterministic JUnit baseline and writes `docs/eval/report.md`; it is not yet exposed as an operator-facing API or dashboard.
- Scanned PDFs/OCR, complex table reconstruction, and richer layout-aware extraction are still future work.
- JSON text ingestion still carries request content in memory; restart-safe source recovery applies to the multipart upload path.
- Chunking is still character-window based, but now operates on structured extracted blocks and preserves page/table metadata. Semantic/recursive splitting is not implemented yet.
- Authentication/RBAC is implemented for the MVP backend. User administration APIs and self-service registration are still future work.
- PowerShell `Invoke-WebRequest` threw a client-side null-reference error when reading SSE; `curl.exe --no-buffer` successfully verified the stream endpoint.

## Recommended Next Step

Continue completing the project core before spending time on the demo surface.

Recommended order:

1. Improve chunking quality further: semantic/recursive splitting, richer table metadata, and safer chunk boundaries.
2. Add scanned-PDF/OCR handling and deeper table extraction as ingestion enhancements.
3. Run the OSS smoke test against a real bucket in the target deployment environment.
4. Align remaining architecture debt: outbox `DONE` vs `PROCESSED` vocabulary, `SYNCING` timeout default, and operational retry/recovery docs.
5. Start preparing the demo surface and interview walkthrough after the ingestion-quality pass.

Demo surface and interview materials can wait until the project core is closer to complete.
