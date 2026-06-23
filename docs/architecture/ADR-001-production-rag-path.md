# ADR-001: Production RAG Path

## Status

Accepted

## Context

KnowSource needs strict production controls around enterprise RAG:

- `kb_id` must be bound on the server side.
- Retrieval must filter by document lifecycle state and `index_status`.
- Vector rows must match documents by exact `(doc_id, doc_version)`.
- Publish/index work is driven by an outbox and observable status transitions.
- Answers need explicit refusal, citations, streaming completion metadata, and later QaTrace and rerank.

Spring AI provides useful model integration and RAG abstractions, but using Spring AI `VectorStore` or
`RetrievalAugmentationAdvisor` as the production path would split these controls across framework internals and
project services.

## Decision

Production RAG uses SQL-first vector writes, SQL-first retrieval, and application-level lightweight orchestration.

Spring AI remains the model integration layer:

- `EmbeddingModel` is adapted through `DocumentEmbeddingGateway`.
- `ChatClient` is used for chat generation.
- Spring AI `VectorStore` and RAG Advisor APIs are limited to spike/reference work unless a later ADR changes this.

The production boundaries are:

- `VectorIndexService` is the only production vector indexing entry point.
- `VectorSearchService` is the only production vector retrieval entry point.
- `ChatService` owns production RAG orchestration, including refusal, sources, streaming protocol, and future QaTrace.
- `/api/spike/**` is disabled by default and is not part of the production path.

## Consequences

This keeps the core enterprise controls explicit, testable, and easy to explain.

W5 improvements such as rerank, profile routing, query rewrite, and QaTrace should be inserted into the current
service-level pipeline rather than by introducing a parallel Advisor pipeline.

Spring AI Advisor integration is a post-W5 optional evolution, not a W5 production dependency. It should only be
reconsidered after the current SQL-first pipeline can be demonstrated stably with retrieval, refusal, citations,
streaming, rerank/profile routing, and QaTrace.

If Spring AI Advisor support is reconsidered later, it must bridge to the existing SQL-first services and preserve the
same citation, refusal, trace, ACL, and version-matching guarantees. It must not introduce a second production retrieval
or indexing path.
