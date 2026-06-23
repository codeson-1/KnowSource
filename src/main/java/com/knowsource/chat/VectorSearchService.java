package com.knowsource.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

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
    private final double maxDistance;

    VectorSearchService(
            JdbcClient jdbcClient,
            ObjectProvider<DocumentEmbeddingGateway> embeddingGatewayProvider,
            @Value("${knowsource.retrieval.max-distance:0.8}") double maxDistance) {
        this.jdbcClient = jdbcClient;
        this.embeddingGatewayProvider = embeddingGatewayProvider;
        this.maxDistance = maxDistance;
    }

    List<RetrievedChunk> search(String kbId, String question, Integer requestedTopK) {
        DocumentEmbeddingGateway embeddingGateway = embeddingGatewayProvider.getIfAvailable();
        if (embeddingGateway == null) {
            throw new IllegalStateException("Document embedding gateway is not available.");
        }

        String normalizedQuestion = normalizeQuestion(question);
        int topK = normalizeTopK(requestedTopK);
        List<float[]> embeddings = embeddingGateway.embed(List.of(normalizedQuestion));
        if (embeddings.size() != 1) {
            throw new IllegalStateException("Embedding result count does not match query count.");
        }

        return jdbcClient.sql("""
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
                .param("topK", topK)
                .query(VectorSearchService::mapChunk)
                .list();
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
