package com.knowsource.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.knowsource.ai.AiProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DashScopeDocumentRerankerTest {

    @Test
    void reordersChunksUsingProviderIndexes() {
        DashScopeDocumentReranker reranker = new DashScopeDocumentReranker(
                provider(() -> List.of(1, 0)),
                new LexicalDocumentReranker());

        List<RetrievedChunk> reranked = reranker.rerank("annual leave", chunks(), 2);

        assertThat(reranked).extracting(RetrievedChunk::docId).containsExactly("doc-b", "doc-a");
    }

    @Test
    void appendsOriginalCandidatesWhenProviderReturnsTooFewIndexes() {
        DashScopeDocumentReranker reranker = new DashScopeDocumentReranker(
                provider(() -> List.of(1)),
                new LexicalDocumentReranker());

        List<RetrievedChunk> reranked = reranker.rerank("annual leave", chunks(), 3);

        assertThat(reranked).extracting(RetrievedChunk::docId).containsExactly("doc-b", "doc-a", "doc-c");
    }

    @Test
    void fallsBackToLexicalRerankerWhenProviderFails() {
        DashScopeDocumentReranker reranker = new DashScopeDocumentReranker(
                provider(() -> {
                    throw new AiProviderException("AI rerank call failed.", new IllegalStateException("synthetic"));
                }),
                new LexicalDocumentReranker());

        List<RetrievedChunk> reranked = reranker.rerank("annual leave", chunks(), 1);

        assertThat(reranked).extracting(RetrievedChunk::docId).containsExactly("doc-b");
    }

    private static List<RetrievedChunk> chunks() {
        return List.of(
                chunk("doc-a", "Security badges are required in the office.", 0.9d),
                chunk("doc-b", "Annual leave is 10 days. Approval is required.", 0.5d),
                chunk("doc-c", "Employees submit requests through the HR portal.", 0.8d));
    }

    private static RetrievedChunk chunk(String docId, String content, double score) {
        return new RetrievedChunk(
                docId + "-chunk",
                docId,
                1,
                docId,
                content,
                docId + "-parent",
                0,
                null,
                "TEXT",
                1.0d - score,
                score);
    }

    private static ObjectProvider<RerankProviderClient> provider(RerankFunction rerankFunction) {
        return new ObjectProvider<>() {
            @Override
            public RerankProviderClient getObject(Object... args) {
                return getObject();
            }

            @Override
            public RerankProviderClient getIfAvailable() {
                return getObject();
            }

            @Override
            public RerankProviderClient getIfUnique() {
                return getObject();
            }

            @Override
            public RerankProviderClient getObject() {
                return (question, documents, topN) -> rerankFunction.rerank();
            }
        };
    }

    private interface RerankFunction {

        List<Integer> rerank();
    }
}
