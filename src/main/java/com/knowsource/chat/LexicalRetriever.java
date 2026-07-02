package com.knowsource.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import com.knowsource.index.TextTokenizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class LexicalRetriever {

    private final JdbcClient jdbcClient;
    private final double threshold;

    LexicalRetriever(
            JdbcClient jdbcClient,
            @Value("${knowsource.retrieval.lexical.threshold:0.0}") double threshold) {
        this.jdbcClient = jdbcClient;
        this.threshold = Math.max(0.0d, threshold);
    }

    List<RetrievedChunk> retrieve(String kbId, String query, int limit, int minKeywordTokens) {
        String queryTokens = TextTokenizer.joinForTsv(query);
        if (!StringUtils.hasText(queryTokens) || TextTokenizer.tokenize(query).size() < minKeywordTokens) {
            return List.of();
        }

        return jdbcClient.sql("""
                WITH query AS (
                    SELECT plainto_tsquery('simple', :queryTokens) AS tsq
                )
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
                    ts_rank_cd(vs.content_tsv, query.tsq) AS lexical_score
                FROM vector_store vs
                CROSS JOIN query
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
                  AND vs.content_tsv @@ query.tsq
                  AND ts_rank_cd(vs.content_tsv, query.tsq) >= :threshold
                ORDER BY lexical_score DESC, vs.doc_id, chunk_index
                LIMIT :topK
                """)
                .param("kbId", kbId)
                .param("queryTokens", queryTokens)
                .param("threshold", threshold)
                .param("topK", limit)
                .query(LexicalRetriever::mapChunk)
                .list();
    }

    private static RetrievedChunk mapChunk(ResultSet rs, int rowNum) throws SQLException {
        double lexicalScore = rs.getDouble("lexical_score");
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
                1.0d,
                lexicalScore,
                RetrievedChunk.RetrievalSource.LEXICAL,
                null,
                rowNum + 1,
                null,
                lexicalScore,
                0.0d);
    }
}
