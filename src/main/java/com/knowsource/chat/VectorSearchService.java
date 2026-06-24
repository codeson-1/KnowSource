package com.knowsource.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.knowsource.ai.AiProviderException;
import com.knowsource.index.DocumentEmbeddingGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class VectorSearchService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 15;

    private final JdbcClient jdbcClient;
    private final ObjectProvider<DocumentEmbeddingGateway> embeddingGatewayProvider;
    private final DocumentReranker documentReranker;
    private final double maxDistance;
    private final int candidateTopK;

    VectorSearchService(
            JdbcClient jdbcClient,
            ObjectProvider<DocumentEmbeddingGateway> embeddingGatewayProvider,
            DocumentReranker documentReranker,
            @Value("${knowsource.retrieval.max-distance:0.8}") double maxDistance,
            @Value("${knowsource.retrieval.candidate-top-k:15}") int candidateTopK) {
        this.jdbcClient = jdbcClient;
        this.embeddingGatewayProvider = embeddingGatewayProvider;
        this.documentReranker = documentReranker;
        this.maxDistance = maxDistance;
        this.candidateTopK = normalizeCandidateTopK(candidateTopK);
    }

    List<RetrievedChunk> search(String kbId, String question, Integer requestedTopK) {
        String normalizedQuestion = normalizeQuestion(question);
        int topK = normalizeTopK(requestedTopK);
        List<RetrievedChunk> candidates = searchCandidates(kbId, normalizedQuestion, Math.max(topK, candidateTopK));
        return documentReranker.rerank(normalizedQuestion, candidates, topK);
    }

    List<RetrievedChunk> search(String kbId, List<String> queries, Integer requestedTopK) {
        List<String> normalizedQueries = queries.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (normalizedQueries.isEmpty()) {
            throw new IllegalArgumentException("Question is required.");
        }
        if (normalizedQueries.size() == 1) {
            return search(kbId, normalizedQueries.getFirst(), requestedTopK);
        }

        int topK = normalizeTopK(requestedTopK);
        int candidateLimit = Math.max(topK, candidateTopK);
        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
        for (String query : normalizedQueries) {
            for (RetrievedChunk chunk : searchCandidates(kbId, query, candidateLimit)) {
                merged.merge(chunkKey(chunk), chunk, VectorSearchService::higherScore);
            }
        }

        List<RetrievedChunk> candidates = new ArrayList<>(merged.values());
        candidates.sort(Comparator.comparingDouble(RetrievedChunk::distance));
        return documentReranker.rerank(normalizedQueries.getFirst(), candidates, topK);
    }

    private List<RetrievedChunk> searchCandidates(String kbId, String question, int candidateLimit) {
        DocumentEmbeddingGateway embeddingGateway = embeddingGatewayProvider.getIfAvailable();
        if (embeddingGateway == null) {
            throw new IllegalStateException("Document embedding gateway is not available.");
        }

        String normalizedQuestion = normalizeQuestion(question);
        List<float[]> embeddings;
        try {
            embeddings = embeddingGateway.embedQuery(normalizedQuestion);
        } catch (AiProviderException ex) {
            return List.of();
        }
        if (embeddings.size() != 1) {
            throw new IllegalStateException("Embedding result count does not match query count.");
        }

        List<RetrievedChunk> candidates = jdbcClient.sql("""
                SELECT
                    vs.metadata ->> 'chunkId' AS chunk_id,
                    vs.doc_id,
                    vs.doc_version,
                    d.title,
                    vs.content,
                    vs.metadata ->> 'parentChunkId' AS parent_chunk_id,
                    COALESCE((vs.metadata ->> 'chunkIndex')::int, 0) AS chunk_index,
                    c.page_number,
                    COALESCE(vs.metadata ->> 'chunkType', c.chunk_type) AS chunk_type,
                    vs.embedding <=> CAST(:queryEmbedding AS vector) AS distance
                FROM vector_store vs
                JOIN documents d
                  ON d.id = vs.doc_id
                 AND d.version = vs.doc_version
                 AND d.kb_id = vs.kb_id
                LEFT JOIN chunk_children c
                  ON c.id = vs.metadata ->> 'chunkId'
                 AND c.doc_id = vs.doc_id
                 AND c.doc_version = vs.doc_version
                WHERE vs.kb_id = :kbId
                  AND vs.status = 'published'
                  AND d.status = 'PUBLISHED'
                  AND d.index_status = 'SYNCED'
                  AND vs.embedding <=> CAST(:queryEmbedding AS vector) <= :maxDistance
                ORDER BY vs.embedding <=> CAST(:queryEmbedding AS vector)
                LIMIT :topK
                """)
                .param("kbId", kbId)
                .param("queryEmbedding", vectorLiteral(embeddings.getFirst()))
                .param("maxDistance", maxDistance)
                .param("topK", candidateLimit)
                .query(VectorSearchService::mapChunk)
                .list();
        return candidates;
    }

    private static String chunkKey(RetrievedChunk chunk) {
        return chunk.docId() + ":" + chunk.docVersion() + ":" + chunk.chunkId();
    }

    private static RetrievedChunk higherScore(RetrievedChunk left, RetrievedChunk right) {
        return left.score() >= right.score() ? left : right;
    }

    private static RetrievedChunk mapChunk(ResultSet rs, int rowNum) throws SQLException {
        double distance = rs.getDouble("distance");
        return new RetrievedChunk(
                rs.getString("chunk_id"),
                rs.getString("doc_id"),
                rs.getInt("doc_version"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("parent_chunk_id"),
                rs.getInt("chunk_index"),
                (Integer) rs.getObject("page_number"),
                rs.getString("chunk_type"),
                distance,
                Math.max(0.0d, 1.0d - distance));
    }

    private static String normalizeQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("Question is required.");
        }
        return question.trim();
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

    private static int normalizeCandidateTopK(int candidateTopK) {
        if (candidateTopK < DEFAULT_TOP_K) {
            return DEFAULT_TOP_K;
        }
        return Math.min(candidateTopK, MAX_TOP_K);
    }

    private static String vectorLiteral(float[] embedding) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(Float.toString(embedding[i]));
        }
        return builder.append(']').toString();
    }
}
