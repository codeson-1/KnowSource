package com.knowsource.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowsource.document.ResourceNotFoundException;
import com.knowsource.user.DemoUserService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
class QaTraceService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int ANSWER_PREVIEW_LENGTH = 120;
    private static final TypeReference<List<SourceCitation>> SOURCE_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final DemoUserService demoUserService;

    QaTraceService(JdbcClient jdbcClient, ObjectMapper objectMapper, DemoUserService demoUserService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.demoUserService = demoUserService;
    }

    @Async("traceExecutor")
    public void recordAsync(QaTraceRecord record) {
        record(record);
    }

    void record(QaTraceRecord record) {
        jdbcClient.sql("""
                INSERT INTO qa_traces (
                    id, user_id, kb_id, query, rewritten_query, retrieved_chunks, answer,
                    retrieval_ms, llm_ms, rewrite_llm_ms, generation_first_token_ms,
                    total_ms, token_usage, rag_profile
                )
                VALUES (
                    :id, :userId, :kbId, :query, :rewrittenQuery, CAST(:retrievedChunks AS jsonb), :answer,
                    :retrievalMs, :llmMs, :rewriteLlmMs, :generationFirstTokenMs,
                    :totalMs, CAST(:tokenUsage AS jsonb), :ragProfile
                )
                """)
                .param("id", record.id())
                .param("userId", record.userId())
                .param("kbId", record.kbId())
                .param("query", record.query())
                .param("rewrittenQuery", record.rewrittenQuery())
                .param("retrievedChunks", toJson(record.retrievedChunks()))
                .param("answer", record.answer())
                .param("retrievalMs", record.retrievalMs())
                .param("llmMs", record.llmMs())
                .param("rewriteLlmMs", record.rewriteLlmMs())
                .param("generationFirstTokenMs", record.generationFirstTokenMs())
                .param("totalMs", record.totalMs())
                .param("tokenUsage", "{}")
                .param("ragProfile", record.ragProfile())
                .update();
    }

    List<QaTraceSummaryResponse> listRecent(String kbId, Integer requestedLimit) {
        requireKbMember(kbId);
        int limit = normalizeLimit(requestedLimit);
        return jdbcClient.sql("""
                SELECT
                    id,
                    kb_id,
                    query,
                    answer,
                    COALESCE(jsonb_array_length(retrieved_chunks), 0) AS source_count,
                    total_ms,
                    rag_profile,
                    created_at
                FROM qa_traces
                WHERE kb_id = :kbId
                ORDER BY created_at DESC
                LIMIT :limit
                """)
                .param("kbId", kbId)
                .param("limit", limit)
                .query(QaTraceService::mapSummary)
                .list();
    }

    QaTraceDetailResponse getTrace(String kbId, String traceId) {
        requireKbMember(kbId);
        return jdbcClient.sql("""
                SELECT
                    id,
                    kb_id,
                    user_id,
                    query,
                    rewritten_query,
                    retrieved_chunks::text AS retrieved_chunks,
                    answer,
                    retrieval_ms,
                    llm_ms,
                    rewrite_llm_ms,
                    generation_first_token_ms,
                    total_ms,
                    token_usage::text AS token_usage,
                    rag_profile,
                    created_at
                FROM qa_traces
                WHERE kb_id = :kbId AND id = :traceId
                """)
                .param("kbId", kbId)
                .param("traceId", traceId)
                .query(this::mapDetail)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("QA trace not found."));
    }

    private void requireKbMember(String kbId) {
        long userId = demoUserService.currentUserId();
        Long membershipCount = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM kb_members
                WHERE kb_id = :kbId AND user_id = :userId
                """)
                .param("kbId", kbId)
                .param("userId", userId)
                .query(Long.class)
                .single();

        if (membershipCount == 0) {
            throw new ResourceNotFoundException("Knowledge base not found.");
        }
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and 100.");
        }
        return limit;
    }

    private static QaTraceSummaryResponse mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new QaTraceSummaryResponse(
                rs.getString("id"),
                rs.getString("kb_id"),
                rs.getString("query"),
                preview(rs.getString("answer")),
                rs.getInt("source_count"),
                (Integer) rs.getObject("total_ms"),
                rs.getString("rag_profile"),
                rs.getObject("created_at", LocalDateTime.class));
    }

    private QaTraceDetailResponse mapDetail(ResultSet rs, int rowNum) throws SQLException {
        return new QaTraceDetailResponse(
                rs.getString("id"),
                rs.getString("kb_id"),
                rs.getLong("user_id"),
                rs.getString("query"),
                rs.getString("rewritten_query"),
                fromJson(rs.getString("retrieved_chunks"), SOURCE_LIST_TYPE),
                rs.getString("answer"),
                (Integer) rs.getObject("retrieval_ms"),
                (Integer) rs.getObject("llm_ms"),
                (Integer) rs.getObject("rewrite_llm_ms"),
                (Integer) rs.getObject("generation_first_token_ms"),
                (Integer) rs.getObject("total_ms"),
                fromJson(rs.getString("token_usage"), JsonNode.class),
                rs.getString("rag_profile"),
                rs.getObject("created_at", LocalDateTime.class));
    }

    private static String preview(String answer) {
        if (answer == null || answer.length() <= ANSWER_PREVIEW_LENGTH) {
            return answer;
        }
        return answer.substring(0, ANSWER_PREVIEW_LENGTH) + "...";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize QA trace payload.", ex);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse QA trace payload.", ex);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse QA trace payload.", ex);
        }
    }
}
