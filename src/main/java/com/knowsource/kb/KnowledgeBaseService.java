package com.knowsource.kb;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import com.knowsource.security.CurrentUserService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeBaseService {

    private static final int MAX_NAME_LENGTH = 128;

    private final JdbcClient jdbcClient;
    private final CurrentUserService currentUserService;

    public KnowledgeBaseService(JdbcClient jdbcClient, CurrentUserService currentUserService) {
        this.jdbcClient = jdbcClient;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request) {
        String name = normalizeName(request.name());
        String description = normalizeDescription(request.description());
        long ownerId = currentUserService.currentUserId();
        String kbId = UUID.randomUUID().toString();

        KnowledgeBaseResponse response = jdbcClient.sql("""
                INSERT INTO knowledge_bases (id, name, description, owner_id)
                VALUES (:id, :name, :description, :ownerId)
                RETURNING id, name, description, owner_id, created_at
                """)
                .param("id", kbId)
                .param("name", name)
                .param("description", description)
                .param("ownerId", ownerId)
                .query(KnowledgeBaseService::mapKnowledgeBase)
                .single();

        jdbcClient.sql("""
                INSERT INTO kb_members (kb_id, user_id, role)
                VALUES (:kbId, :userId, 'OWNER')
                """)
                .param("kbId", kbId)
                .param("userId", ownerId)
                .update();

        return response;
    }

    public List<KnowledgeBaseResponse> listMine() {
        long userId = currentUserService.currentUserId();

        return jdbcClient.sql("""
                SELECT kb.id, kb.name, kb.description, kb.owner_id, kb.created_at
                FROM knowledge_bases kb
                JOIN kb_members member ON member.kb_id = kb.id
                WHERE member.user_id = :userId
                ORDER BY kb.created_at DESC, kb.id DESC
                """)
                .param("userId", userId)
                .query(KnowledgeBaseService::mapKnowledgeBase)
                .list();
    }

    private String normalizeName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("Knowledge base name is required.");
        }

        String normalized = name.trim();
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Knowledge base name must be at most 128 characters.");
        }
        return normalized;
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }

        String normalized = description.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static KnowledgeBaseResponse mapKnowledgeBase(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeBaseResponse(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getLong("owner_id"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
