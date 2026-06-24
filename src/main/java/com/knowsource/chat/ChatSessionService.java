package com.knowsource.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import com.knowsource.document.ResourceNotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class ChatSessionService {

    private static final int HISTORY_LIMIT = 12;
    private static final int TITLE_LENGTH = 80;

    private final JdbcClient jdbcClient;

    ChatSessionService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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
        appendMessage(sessionId, "ASSISTANT", content);
    }

    private void appendMessage(String sessionId, String role, String content) {
        jdbcClient.sql("""
                INSERT INTO chat_messages (session_id, role, content)
                VALUES (:sessionId, :role, :content)
                """)
                .param("sessionId", sessionId)
                .param("role", role)
                .param("content", content)
                .update();
        jdbcClient.sql("UPDATE chat_sessions SET updated_at = NOW() WHERE id = :sessionId")
                .param("sessionId", sessionId)
                .update();
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

    private static String title(String question) {
        if (question.length() <= TITLE_LENGTH) {
            return question;
        }
        return question.substring(0, TITLE_LENGTH);
    }
}
