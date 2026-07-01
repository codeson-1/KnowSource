package com.knowsource.kb;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.knowsource.document.ResourceNotFoundException;
import com.knowsource.security.CurrentUser;
import com.knowsource.security.CurrentUserService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeBaseService {

    private static final int MAX_NAME_LENGTH = 128;

    private final JdbcClient jdbcClient;
    private final CurrentUserService currentUserService;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeBaseService(
            JdbcClient jdbcClient,
            CurrentUserService currentUserService,
            TransactionTemplate transactionTemplate) {
        this.jdbcClient = jdbcClient;
        this.currentUserService = currentUserService;
        this.transactionTemplate = transactionTemplate;
    }

    @Transactional
    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request) {
        String name = normalizeName(request.name());
        String description = normalizeDescription(request.description());
        CurrentUser user = currentUserService.currentUser();
        requireKnowledgeBaseCreateAccess(user);
        long ownerId = user.id();
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
                .query((rs, rowNum) -> mapKnowledgeBase(rs, rowNum, "OWNER"))
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
                SELECT kb.id, kb.name, kb.description, kb.owner_id, kb.created_at, member.role AS member_role
                FROM knowledge_bases kb
                JOIN kb_members member ON member.kb_id = kb.id
                WHERE member.user_id = :userId
                ORDER BY kb.created_at DESC, kb.id DESC
                """)
                .param("userId", userId)
                .query(KnowledgeBaseService::mapKnowledgeBase)
                .list();
    }

    public KnowledgeBaseResponse get(String kbId) {
        long userId = currentUserService.currentUserId();
        requireKbMember(kbId, userId);
        return findKnowledgeBase(kbId);
    }

    public KnowledgeBaseResponse update(String kbId, UpdateKnowledgeBaseRequest request) {
        CurrentUser user = currentUserService.currentUser();
        requireKbOwnerOrAdmin(kbId, user);
        String name = normalizeName(request.name());
        String description = normalizeDescription(request.description());
        return jdbcClient.sql("""
                UPDATE knowledge_bases kb
                SET name = :name,
                    description = :description
                FROM kb_members member
                WHERE kb.id = :kbId
                  AND member.kb_id = kb.id
                  AND member.user_id = :userId
                RETURNING kb.id, kb.name, kb.description, kb.owner_id, kb.created_at, member.role AS member_role
                """)
                .param("kbId", kbId)
                .param("userId", user.id())
                .param("name", name)
                .param("description", description)
                .query(KnowledgeBaseService::mapKnowledgeBase)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge base not found."));
    }

    public void delete(String kbId) {
        CurrentUser user = currentUserService.currentUser();
        requireKbOwnerOrAdmin(kbId, user);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("""
                    DELETE FROM vector_store
                    WHERE kb_id = :kbId
                    """)
                    .param("kbId", kbId)
                    .update();
            jdbcClient.sql("""
                    DELETE FROM qa_traces
                    WHERE kb_id = :kbId
                    """)
                    .param("kbId", kbId)
                    .update();
            jdbcClient.sql("""
                    DELETE FROM chat_messages
                    WHERE session_id IN (SELECT id FROM chat_sessions WHERE kb_id = :kbId)
                    """)
                    .param("kbId", kbId)
                    .update();
            jdbcClient.sql("DELETE FROM chat_sessions WHERE kb_id = :kbId")
                    .param("kbId", kbId)
                    .update();
            jdbcClient.sql("""
                    DELETE FROM document_publish_events
                    WHERE kb_id = :kbId
                    """)
                    .param("kbId", kbId)
                    .update();
            jdbcClient.sql("""
                    DELETE FROM chunk_children
                    WHERE doc_id IN (SELECT id FROM documents WHERE kb_id = :kbId)
                    """)
                    .param("kbId", kbId)
                    .update();
            jdbcClient.sql("""
                    DELETE FROM chunk_parents
                    WHERE doc_id IN (SELECT id FROM documents WHERE kb_id = :kbId)
                    """)
                    .param("kbId", kbId)
                    .update();
            jdbcClient.sql("""
                    DELETE FROM ingest_tasks
                    WHERE doc_id IN (SELECT id FROM documents WHERE kb_id = :kbId)
                    """)
                    .param("kbId", kbId)
                    .update();
            jdbcClient.sql("DELETE FROM documents WHERE kb_id = :kbId")
                    .param("kbId", kbId)
                    .update();
            jdbcClient.sql("DELETE FROM kb_members WHERE kb_id = :kbId")
                    .param("kbId", kbId)
                    .update();
            int deleted = jdbcClient.sql("DELETE FROM knowledge_bases WHERE id = :kbId")
                    .param("kbId", kbId)
                    .update();
            if (deleted == 0) {
                throw new ResourceNotFoundException("Knowledge base not found.");
            }
        });
    }

    public List<KnowledgeBaseMemberResponse> listMembers(String kbId) {
        long userId = currentUserService.currentUserId();
        requireKbMember(kbId, userId);
        return jdbcClient.sql("""
                SELECT u.id AS user_id, u.username, u.global_role, member.role AS member_role
                FROM kb_members member
                JOIN users u ON u.id = member.user_id
                WHERE member.kb_id = :kbId
                ORDER BY member.role ASC, u.username ASC
                """)
                .param("kbId", kbId)
                .query(KnowledgeBaseService::mapMember)
                .list();
    }

    public KnowledgeBaseMemberResponse addMember(String kbId, MemberRequest request) {
        CurrentUser user = currentUserService.currentUser();
        requireKbOwnerOrAdmin(kbId, user);
        String username = normalizeUsername(request.username());
        String role = normalizeMemberRole(request.role());
        long memberUserId = userIdByUsername(username);

        jdbcClient.sql("""
                INSERT INTO kb_members (kb_id, user_id, role)
                VALUES (:kbId, :userId, :role)
                ON CONFLICT (kb_id, user_id) DO UPDATE SET role = EXCLUDED.role
                """)
                .param("kbId", kbId)
                .param("userId", memberUserId)
                .param("role", role)
                .update();
        return findMember(kbId, memberUserId);
    }

    public KnowledgeBaseMemberResponse updateMember(String kbId, long userId, MemberRequest request) {
        CurrentUser user = currentUserService.currentUser();
        requireKbOwnerOrAdmin(kbId, user);
        String role = normalizeMemberRole(request.role());
        String currentRole = memberRole(kbId, userId);
        if ("OWNER".equals(currentRole) && !"OWNER".equals(role)) {
            requireAnotherOwner(kbId, userId);
        }
        int updated = jdbcClient.sql("""
                UPDATE kb_members
                SET role = :role
                WHERE kb_id = :kbId AND user_id = :userId
                """)
                .param("kbId", kbId)
                .param("userId", userId)
                .param("role", role)
                .update();
        if (updated == 0) {
            throw new ResourceNotFoundException("Knowledge base member not found.");
        }
        return findMember(kbId, userId);
    }

    public void removeMember(String kbId, long userId) {
        CurrentUser user = currentUserService.currentUser();
        requireKbOwnerOrAdmin(kbId, user);
        String currentRole = memberRole(kbId, userId);
        if ("OWNER".equals(currentRole)) {
            requireAnotherOwner(kbId, userId);
        }
        int deleted = jdbcClient.sql("""
                DELETE FROM kb_members
                WHERE kb_id = :kbId AND user_id = :userId
                """)
                .param("kbId", kbId)
                .param("userId", userId)
                .update();
        if (deleted == 0) {
            throw new ResourceNotFoundException("Knowledge base member not found.");
        }
    }

    private KnowledgeBaseResponse findKnowledgeBase(String kbId) {
        long userId = currentUserService.currentUserId();
        return jdbcClient.sql("""
                SELECT kb.id, kb.name, kb.description, kb.owner_id, kb.created_at, member.role AS member_role
                FROM knowledge_bases kb
                JOIN kb_members member ON member.kb_id = kb.id
                WHERE kb.id = :kbId AND member.user_id = :userId
                """)
                .param("kbId", kbId)
                .param("userId", userId)
                .query(KnowledgeBaseService::mapKnowledgeBase)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge base not found."));
    }

    private void requireKbMember(String kbId, long userId) {
        Long count = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM kb_members
                WHERE kb_id = :kbId AND user_id = :userId
                """)
                .param("kbId", kbId)
                .param("userId", userId)
                .query(Long.class)
                .single();
        if (count == 0) {
            throw new ResourceNotFoundException("Knowledge base not found.");
        }
    }

    private void requireKbOwnerOrAdmin(String kbId, CurrentUser user) {
        String role = jdbcClient.sql("""
                SELECT role
                FROM kb_members
                WHERE kb_id = :kbId AND user_id = :userId
                """)
                .param("kbId", kbId)
                .param("userId", user.id())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge base not found."));
        if (!"ADMIN".equals(user.globalRole()) && !"OWNER".equals(role)) {
            throw new AccessDeniedException("Knowledge base owner access is required.");
        }
    }

    private void requireKnowledgeBaseCreateAccess(CurrentUser user) {
        if (!Set.of("ADMIN", "EDITOR").contains(user.globalRole())) {
            throw new AccessDeniedException("Knowledge base creation requires ADMIN or EDITOR access.");
        }
    }

    private long userIdByUsername(String username) {
        return jdbcClient.sql("SELECT id FROM users WHERE username = :username")
                .param("username", username)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
    }

    private String memberRole(String kbId, long userId) {
        return jdbcClient.sql("""
                SELECT role
                FROM kb_members
                WHERE kb_id = :kbId AND user_id = :userId
                """)
                .param("kbId", kbId)
                .param("userId", userId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge base member not found."));
    }

    private void requireAnotherOwner(String kbId, long userId) {
        Long ownerCount = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM kb_members
                WHERE kb_id = :kbId AND role = 'OWNER' AND user_id <> :userId
                """)
                .param("kbId", kbId)
                .param("userId", userId)
                .query(Long.class)
                .single();
        if (ownerCount == 0) {
            throw new IllegalArgumentException("Knowledge base must keep at least one OWNER.");
        }
    }

    private KnowledgeBaseMemberResponse findMember(String kbId, long userId) {
        return jdbcClient.sql("""
                SELECT u.id AS user_id, u.username, u.global_role, member.role AS member_role
                FROM kb_members member
                JOIN users u ON u.id = member.user_id
                WHERE member.kb_id = :kbId AND member.user_id = :userId
                """)
                .param("kbId", kbId)
                .param("userId", userId)
                .query(KnowledgeBaseService::mapMember)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge base member not found."));
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

    private String normalizeUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("Username is required.");
        }
        return username.trim();
    }

    private String normalizeMemberRole(String role) {
        if (!StringUtils.hasText(role)) {
            throw new IllegalArgumentException("Member role is required.");
        }
        String normalized = role.trim().toUpperCase();
        if (!Set.of("OWNER", "EDITOR", "VIEWER").contains(normalized)) {
            throw new IllegalArgumentException("Member role must be OWNER, EDITOR, or VIEWER.");
        }
        return normalized;
    }

    private static KnowledgeBaseResponse mapKnowledgeBase(ResultSet rs, int rowNum) throws SQLException {
        return mapKnowledgeBase(rs, rowNum, rs.getString("member_role"));
    }

    private static KnowledgeBaseResponse mapKnowledgeBase(ResultSet rs, int rowNum, String memberRole) throws SQLException {
        return new KnowledgeBaseResponse(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getLong("owner_id"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                memberRole);
    }

    private static KnowledgeBaseMemberResponse mapMember(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeBaseMemberResponse(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("global_role"),
                rs.getString("member_role"));
    }
}
