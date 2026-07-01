package com.knowsource.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowsource.document.ResourceNotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class ChatSessionService {

    private static final int HISTORY_LIMIT = 12;
    private static final int TITLE_LENGTH = 80;
    private static final int DEFAULT_SESSION_LIMIT = 30;
    private static final int MAX_SESSION_LIMIT = 100;
    private static final int PREVIEW_LENGTH = 96;
    private static final TypeReference<List<SourceCitation>> SOURCE_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    ChatSessionService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    ChatSessionHistory loadOrCreate(String requestedSessionId, long userId, String kbId, String firstQuestion) {
        if (StringUtils.hasText(requestedSessionId)) {
            String sessionId = requestedSessionId.trim();
            requireOwnedSession(sessionId, userId, kbId);
            return new ChatSessionHistory(sessionId, false, loadHistory(sessionId));
        }

        String sessionId = UUID.randomUUID().toString();
        jdbcClient.sql("""
                INSERT INTO chat_sessions (id, user_id, kb_id, title)
                VALUES (:id, :userId, :kbId, :title)
                """)
                .param("id", sessionId)
                .param("userId", userId)
                .param("kbId", kbId)
                .param("title", title(firstQuestion))
                .update();
        return new ChatSessionHistory(sessionId, true, List.of());
    }

    void appendUserMessage(String sessionId, String content) {
        appendMessage(sessionId, "USER", content);
    }

    void appendAssistantMessage(String sessionId, String content) {
        appendAssistantMessage(sessionId, content, null);
    }

    void appendAssistantMessage(String sessionId, String content, String qaTraceId) {
        appendMessage(sessionId, "ASSISTANT", content, qaTraceId);
    }

    List<ChatSessionSummaryResponse> listSessions(String kbId, long userId, Integer requestedLimit) {
        requireKbMember(kbId, userId);
        int limit = normalizeLimit(requestedLimit);
        return jdbcClient.sql("""
                SELECT
                    s.id,
                    s.kb_id,
                    s.title,
                    COALESCE(mc.message_count, 0) AS message_count,
                    lm.role AS last_message_role,
                    lm.content AS last_message_content,
                    s.created_at,
                    s.updated_at
                FROM chat_sessions s
                LEFT JOIN (
                    SELECT session_id, COUNT(*) AS message_count
                    FROM chat_messages
                    GROUP BY session_id
                ) mc ON mc.session_id = s.id
                LEFT JOIN LATERAL (
                    SELECT role, content
                    FROM chat_messages
                    WHERE session_id = s.id
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                ) lm ON TRUE
                WHERE s.user_id = :userId AND s.kb_id = :kbId
                ORDER BY s.updated_at DESC
                LIMIT :limit
                """)
                .param("kbId", kbId)
                .param("userId", userId)
                .param("limit", limit)
                .query(ChatSessionService::mapSummary)
                .list();
    }

    ChatSessionDetailResponse getSession(String kbId, long userId, String sessionId) {
        requireOwnedSession(sessionId, userId, kbId);
        ChatSessionHeader header = jdbcClient.sql("""
                SELECT id, kb_id, title, created_at, updated_at
                FROM chat_sessions
                WHERE id = :sessionId
                """)
                .param("sessionId", sessionId)
                .query(ChatSessionService::mapHeader)
                .single();

        List<ChatSessionMessageResponse> messages = jdbcClient.sql("""
                SELECT
                    m.id,
                    m.role,
                    m.content,
                    m.qa_trace_id,
                    t.retrieved_chunks::text AS retrieved_chunks,
                    m.created_at
                FROM chat_messages m
                LEFT JOIN qa_traces t
                  ON t.id = m.qa_trace_id
                 AND t.kb_id = :kbId
                 AND t.user_id = :userId
                WHERE m.session_id = :sessionId
                ORDER BY m.created_at, m.id
                """)
                .param("kbId", kbId)
                .param("userId", userId)
                .param("sessionId", sessionId)
                .query(this::mapDetailMessage)
                .list();

        return new ChatSessionDetailResponse(
                header.id(), header.kbId(), header.title(), header.createdAt(), header.updatedAt(), messages);
    }

    void deleteSession(String kbId, long userId, String sessionId) {
        requireOwnedSession(sessionId, userId, kbId);
        jdbcClient.sql("DELETE FROM chat_messages WHERE session_id = :sessionId")
                .param("sessionId", sessionId)
                .update();
        jdbcClient.sql("DELETE FROM chat_sessions WHERE id = :sessionId")
                .param("sessionId", sessionId)
                .update();
    }

    private void appendMessage(String sessionId, String role, String content) {
        appendMessage(sessionId, role, content, null);
    }

    private void appendMessage(String sessionId, String role, String content, String qaTraceId) {
        jdbcClient.sql("""
                INSERT INTO chat_messages (session_id, role, content, qa_trace_id)
                VALUES (:sessionId, :role, :content, :qaTraceId)
                """)
                .param("sessionId", sessionId)
                .param("role", role)
                .param("content", content)
                .param("qaTraceId", qaTraceId)
                .update();
        jdbcClient.sql("UPDATE chat_sessions SET updated_at = NOW() WHERE id = :sessionId")
                .param("sessionId", sessionId)
                .update();
    }

    private void requireKbMember(String kbId, long userId) {
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

    private void requireOwnedSession(String sessionId, long userId, String kbId) {
        Long count = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM chat_sessions
                WHERE id = :sessionId
                  AND user_id = :userId
                  AND kb_id = :kbId
                """)
                .param("sessionId", sessionId)
                .param("userId", userId)
                .param("kbId", kbId)
                .query(Long.class)
                .single();

        if (count == 0) {
            throw new ResourceNotFoundException("Chat session not found.");
        }
    }

    private List<ChatMessage> loadHistory(String sessionId) {
        return jdbcClient.sql("""
                SELECT role, content
                FROM (
                    SELECT id, role, content, created_at
                    FROM chat_messages
                    WHERE session_id = :sessionId
                    ORDER BY created_at DESC, id DESC
                    LIMIT :limit
                ) recent
                ORDER BY created_at, id
                """)
                .param("sessionId", sessionId)
                .param("limit", HISTORY_LIMIT)
                .query(ChatSessionService::mapMessage)
                .list();
    }

    private static ChatMessage mapMessage(ResultSet rs, int rowNum) throws SQLException {
        return new ChatMessage(rs.getString("role"), rs.getString("content"));
    }

    private static ChatSessionSummaryResponse mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new ChatSessionSummaryResponse(
                rs.getString("id"),
                rs.getString("kb_id"),
                rs.getString("title"),
                rs.getInt("message_count"),
                rs.getString("last_message_role"),
                preview(rs.getString("last_message_content")),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private static ChatSessionHeader mapHeader(ResultSet rs, int rowNum) throws SQLException {
        return new ChatSessionHeader(
                rs.getString("id"),
                rs.getString("kb_id"),
                rs.getString("title"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private ChatSessionMessageResponse mapDetailMessage(ResultSet rs, int rowNum) throws SQLException {
        String role = rs.getString("role");
        String qaTraceId = rs.getString("qa_trace_id");
        String content = rs.getString("content");
        String retrievedChunks = rs.getString("retrieved_chunks");
        List<SourceCitation> sources = "ASSISTANT".equals(role)
                && StringUtils.hasText(qaTraceId)
                && StringUtils.hasText(retrievedChunks)
                && !isLlmRefusal(content)
                ? fromJson(retrievedChunks)
                : List.of();

        return new ChatSessionMessageResponse(
                rs.getLong("id"),
                role,
                content,
                qaTraceId,
                sources,
                rs.getObject("created_at", LocalDateTime.class));
    }

    private List<SourceCitation> fromJson(String json) {
        try {
            return objectMapper.readValue(json, SOURCE_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse chat message sources.", ex);
        }
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_SESSION_LIMIT;
        }
        if (limit < 1 || limit > MAX_SESSION_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and 100.");
        }
        return limit;
    }

    private static String preview(String content) {
        if (content == null || content.length() <= PREVIEW_LENGTH) {
            return content;
        }
        return content.substring(0, PREVIEW_LENGTH) + "...";
    }

    private static boolean isLlmRefusal(String answer) {
        return StringUtils.hasText(answer) && answer.trim().equals(ChatService.LLM_REFUSAL_ANSWER);
    }

    private static String title(String question) {
        if (question.length() <= TITLE_LENGTH) {
            return question;
        }
        return question.substring(0, TITLE_LENGTH);
    }

    private record ChatSessionHeader(
            String id,
            String kbId,
            String title,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }
}
