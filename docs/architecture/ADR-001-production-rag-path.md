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

## Costs and gaps versus design §7.7 (Modular RAG via Advisor chain)

The architecture design §7.7 originally narrates the RAG pipeline as a Spring AI
`RetrievalAugmentationAdvisor` assembled from `QueryTransformer` + `VectorStoreDocumentRetriever` +
`DocumentPostProcessor` + `ContextualQueryAugmenter`. The MVP production path intentionally does **not** use that
assembly. This section records the reason and the cost so the code and the design stay aligned rather than saying one
thing and doing another.

Reason for the deviation:

- The SQL-first services (`VectorIndexService`, `VectorSearchService`, `ChatService`) must own the production controls
  (`kb_id` server-side binding, `(doc_id, doc_version)` exact match, `index_status` filter, refusal, citations,
  streaming protocol, QaTrace). Routing these through `RetrievalAugmentationAdvisor` internals would split the controls
  across framework code that is harder to test and audit at MVP stage.
- The hand-written pipeline (`ChatService.prepareContext` → `VectorSearchService.search` →
  `QueryRewriteService.rewrite` → `DocumentReranker.rerank` → `ChatClient`) is the service-level pipeline referenced in
  the Consequences section above; rerank, profile routing, query rewrite, and QaTrace were inserted here on purpose.

Known cost / gap to close later:

- `ContextualQueryAugmenter.allowEmptyContext(false)` from §7.7 is **not** the production refusal gate. Out-of-scope
  refusal currently combines (1) empty retrieval and (2) a lexical-evidence check (`hasLexicalEvidence`) as a bridge.
  This is a weaker, lexical gate and is recorded as a known MVP shortcut; the long-term target is to express refusal
  through the Advisor's `allowEmptyContext` contract or an equivalent semantic distance threshold.
- The "interface-replaceable" selling point of Spring AI RAG (`spring-ai-rag` is not yet on the classpath) is deferred.
  Re-introducing the Advisor chain is a post-MVP evolution that must preserve every guarantee listed above.

Decision owner note: when the Advisor chain is adopted, update this ADR (or supersede it) and remove the
`hasLexicalEvidence` bridge so the design §7.7 contract and the code converge on a single refusal mechanism.
