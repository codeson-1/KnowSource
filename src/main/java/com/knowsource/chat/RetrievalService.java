package com.knowsource.chat;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class RetrievalService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 15;

    private final VectorSearchService vectorSearchService;
    private final LexicalRetriever lexicalRetriever;
    private final RetrievalFusionService fusionService;
    private final DocumentReranker documentReranker;
    private final RetrievalMode defaultMode;
    private final int vectorTopK;
    private final int lexicalTopK;
    private final int candidateTopK;
    private final int rrfK;
    private final boolean keywordOnRewrites;
    private final int minKeywordTokens;

    RetrievalService(
            VectorSearchService vectorSearchService,
            LexicalRetriever lexicalRetriever,
            RetrievalFusionService fusionService,
            DocumentReranker documentReranker,
            @Value("${knowsource.retrieval.mode:hybrid}") String defaultMode,
            @Value("${knowsource.retrieval.vector-top-k:${knowsource.retrieval.candidate-top-k:15}}") int vectorTopK,
            @Value("${knowsource.retrieval.lexical-top-k:${knowsource.retrieval.candidate-top-k:15}}") int lexicalTopK,
            @Value("${knowsource.retrieval.candidate-top-k:15}") int candidateTopK,
            @Value("${knowsource.retrieval.hybrid.rrf-k:60}") int rrfK,
            @Value("${knowsource.retrieval.hybrid.keyword-on-rewrites:false}") boolean keywordOnRewrites,
            @Value("${knowsource.retrieval.hybrid.min-keyword-tokens:1}") int minKeywordTokens) {
        this.vectorSearchService = vectorSearchService;
        this.lexicalRetriever = lexicalRetriever;
        this.fusionService = fusionService;
        this.documentReranker = documentReranker;
        this.defaultMode = RetrievalMode.from(defaultMode);
        this.vectorTopK = normalizeCandidateLimit(vectorTopK);
        this.lexicalTopK = normalizeCandidateLimit(lexicalTopK);
        this.candidateTopK = normalizeCandidateLimit(candidateTopK);
        this.rrfK = Math.max(1, rrfK);
        this.keywordOnRewrites = keywordOnRewrites;
        this.minKeywordTokens = Math.max(1, minKeywordTokens);
    }

    List<RetrievedChunk> search(String kbId, List<String> queries, Integer requestedTopK, String requestedMode) {
        List<String> normalizedQueries = queries.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (normalizedQueries.isEmpty()) {
            throw new IllegalArgumentException("Question is required.");
        }

        int topK = normalizeTopK(requestedTopK);
        RetrievalMode mode = resolveMode(requestedMode);
        if (mode == RetrievalMode.VECTOR) {
            return vectorSearchService.search(kbId, normalizedQueries, topK);
        }

        List<List<RetrievedChunk>> perQueryResults = new ArrayList<>(normalizedQueries.size());
        for (int i = 0; i < normalizedQueries.size(); i++) {
            String query = normalizedQueries.get(i);
            List<RetrievedChunk> vectorCandidates = mode == RetrievalMode.LEXICAL
                    ? List.of()
                    : safeVectorRetrieve(kbId, query);
            List<RetrievedChunk> lexicalCandidates = shouldRunLexical(mode, i)
                    ? safeLexicalRetrieve(kbId, query)
                    : List.of();

            List<RetrievedChunk> fused = fusionService.fuse(vectorCandidates, lexicalCandidates, rrfK);
            perQueryResults.add(fused.stream().limit(candidateTopK).toList());
        }

        List<RetrievedChunk> candidates = fusionService.mergeAcrossQueries(perQueryResults).stream()
                .limit(candidateTopK)
                .toList();
        return documentReranker.rerank(normalizedQueries.getFirst(), candidates, topK);
    }

    RetrievalMode resolveMode(String requestedMode) {
        return StringUtils.hasText(requestedMode) ? RetrievalMode.from(requestedMode) : defaultMode;
    }

    private boolean shouldRunLexical(RetrievalMode mode, int queryIndex) {
        return mode == RetrievalMode.LEXICAL || queryIndex == 0 || keywordOnRewrites;
    }

    private List<RetrievedChunk> safeLexicalRetrieve(String kbId, String query) {
        try {
            return lexicalRetriever.retrieve(kbId, query, lexicalTopK, minKeywordTokens);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<RetrievedChunk> safeVectorRetrieve(String kbId, String query) {
        try {
            return vectorSearchService.searchCandidates(kbId, query, vectorTopK);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static int normalizeTopK(Integer topK) {
        if (topK == null) {
            return DEFAULT_TOP_K;
        }
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK must be between 1 and 15.");
        }
        return topK;
    }

    private static int normalizeCandidateLimit(int limit) {
        return Math.max(DEFAULT_TOP_K, Math.min(limit, MAX_TOP_K));
    }
}
