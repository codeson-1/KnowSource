package com.knowsource.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.knowsource.ai.AiProviderException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
class DashScopeDocumentReranker implements DocumentReranker {

    private final ObjectProvider<RerankProviderClient> rerankProviderClientProvider;
    private final LexicalDocumentReranker fallbackReranker;

    DashScopeDocumentReranker(
            ObjectProvider<RerankProviderClient> rerankProviderClientProvider,
            LexicalDocumentReranker fallbackReranker) {
        this.rerankProviderClientProvider = rerankProviderClientProvider;
        this.fallbackReranker = fallbackReranker;
    }

    @Override
    public List<RetrievedChunk> rerank(String question, List<RetrievedChunk> chunks, int topK) {
        if (chunks.isEmpty()) {
            return List.of();
        }

        RerankProviderClient rerankProviderClient = rerankProviderClientProvider.getIfAvailable();
        if (rerankProviderClient == null) {
            return fallbackReranker.rerank(question, chunks, topK);
        }

        try {
            List<String> documents = chunks.stream()
                    .map(RetrievedChunk::content)
                    .toList();
            return reorder(chunks, rerankProviderClient.rerank(question, documents, Math.min(topK, chunks.size())), topK);
        } catch (AiProviderException ex) {
            return fallbackReranker.rerank(question, chunks, topK);
        }
    }

    private static List<RetrievedChunk> reorder(List<RetrievedChunk> chunks, List<Integer> orderedIndexes, int topK) {
        Set<Integer> selectedIndexes = new LinkedHashSet<>(orderedIndexes);
        List<RetrievedChunk> reranked = new ArrayList<>(Math.min(topK, chunks.size()));

        for (Integer index : selectedIndexes) {
            if (index != null && index >= 0 && index < chunks.size()) {
                reranked.add(chunks.get(index));
            }
            if (reranked.size() == topK) {
                return reranked;
            }
        }

        for (int i = 0; i < chunks.size() && reranked.size() < topK; i++) {
            if (!selectedIndexes.contains(i)) {
                reranked.add(chunks.get(i));
            }
        }

        return reranked;
    }
}
