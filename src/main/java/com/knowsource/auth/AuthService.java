package com.knowsource.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.knowsource.security.CurrentUser;
import com.knowsource.security.CurrentUserService;
import com.knowsource.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class AuthService {

    private final JdbcClient jdbcClient;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CurrentUserService currentUserService;
    private final TransactionTemplate transactionTemplate;
    private final long refreshTokenTtlDays;

    public AuthService(
            JdbcClient jdbcClient,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            CurrentUserService currentUserService,
            TransactionTemplate transactionTemplate,
            @Value("${knowsource.security.refresh-token-ttl-days:7}") long refreshTokenTtlDays) {
        this.jdbcClient = jdbcClient;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.currentUserService = currentUserService;
        this.transactionTemplate = transactionTemplate;
        this.refreshTokenTtlDays = Math.max(1, refreshTokenTtlDays);
    }

    public AuthResponse login(LoginRequest request) {
        String username = normalize(request.username(), "Username is required.");
        String password = normalize(request.password(), "Password is required.");
        AuthUser user = jdbcClient.sql("""
                SELECT id, username, password_hash, global_role
                FROM users
                WHERE username = :username
                """)
                .param("username", username)
                .query(AuthService::mapUser)
                .optional()
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password."));
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new BadCredentialsException("Invalid username or password.");
        }
        return issueTokens(user.currentUser());
    }

    public AuthResponse register(RegisterRequest request) {
        String username = normalizeUsername(request.username());
        String password = normalizePassword(request.password());
        String email = normalizeEmail(request.email());
        return transactionTemplate.execute(status -> {
            Long existing = jdbcClient.sql("SELECT COUNT(*) FROM users WHERE username = :username")
                    .param("username", username)
                    .query(Long.class)
                    .single();
            if (existing > 0) {
                throw new IllegalArgumentException("Username already exists.");
            }
            AuthUser user = jdbcClient.sql("""
                    INSERT INTO users (username, password_hash, email, global_role)
                    VALUES (:username, :passwordHash, :email, 'VIEWER')
                    RETURNING id, username, password_hash, global_role
                    """)
                    .param("username", username)
                    .param("passwordHash", passwordEncoder.encode(password))
                    .param("email", email)
                    .query(AuthService::mapUser)
                    .single();
            return issueTokens(user.currentUser());
        });
    }

    public AuthResponse refresh(RefreshTokenRequest request) {
        String refreshToken = normalize(request.refreshToken(), "Refresh token is required.");
        String tokenHash = hash(refreshToken);
        return transactionTemplate.execute(status -> {
            CurrentUser user = jdbcClient.sql("""
                    SELECT u.id, u.username, u.global_role
                    FROM refresh_tokens rt
                    JOIN users u ON u.id = rt.user_id
                    WHERE rt.token_hash = :tokenHash
                      AND rt.revoked_at IS NULL
                      AND rt.expires_at > NOW()
                    FOR UPDATE OF rt
                    """)
                    .param("tokenHash", tokenHash)
                    .query((rs, rowNum) -> new CurrentUser(
                            rs.getLong("id"),
                            rs.getString("username"),
                            rs.getString("global_role")))
                    .optional()
                    .orElseThrow(() -> new BadCredentialsException("Invalid refresh token."));

            jdbcClient.sql("UPDATE refresh_tokens SET revoked_at = NOW() WHERE token_hash = :tokenHash")
                    .param("tokenHash", tokenHash)
                    .update();
            return issueTokens(user);
        });
    }

    public void logout(RefreshTokenRequest request) {
        if (request == null || !StringUtils.hasText(request.refreshToken())) {
            return;
        }
        jdbcClient.sql("""
                UPDATE refresh_tokens
                SET revoked_at = COALESCE(revoked_at, NOW())
                WHERE token_hash = :tokenHash
                """)
                .param("tokenHash", hash(request.refreshToken()))
                .update();
    }

    public List<UserResponse> listUsers() {
        requireAdmin();
        return jdbcClient.sql("""
                SELECT id, username, email, global_role, created_at
                FROM users
                ORDER BY created_at DESC, id DESC
                """)
                .query(AuthService::mapUserResponse)
                .list();
    }

    public UserResponse createUser(CreateUserRequest request) {
        requireAdmin();
        String username = normalizeUsername(request.username());
        String password = normalizePassword(request.password());
        String email = normalizeEmail(request.email());
        String globalRole = normalizeGlobalRole(request.globalRole());
        return transactionTemplate.execute(status -> {
            Long existing = jdbcClient.sql("SELECT COUNT(*) FROM users WHERE username = :username")
                    .param("username", username)
                    .query(Long.class)
                    .single();
            if (existing > 0) {
                throw new IllegalArgumentException("Username already exists.");
            }
            return jdbcClient.sql("""
                    INSERT INTO users (username, password_hash, email, global_role)
                    VALUES (:username, :passwordHash, :email, :globalRole)
                    RETURNING id, username, email, global_role, created_at
                    """)
                    .param("username", username)
                    .param("passwordHash", passwordEncoder.encode(password))
                    .param("email", email)
                    .param("globalRole", globalRole)
                    .query(AuthService::mapUserResponse)
                    .single();
        });
    }

    public UserResponse updateUserRole(long userId, UpdateUserRoleRequest request) {
        requireAdmin();
        String globalRole = normalizeGlobalRole(request.globalRole());
        return jdbcClient.sql("""
                UPDATE users
                SET global_role = :globalRole,
                    token_version = token_version + 1
                WHERE id = :userId
                RETURNING id, username, email, global_role, created_at
                """)
                .param("userId", userId)
                .param("globalRole", globalRole)
                .query(AuthService::mapUserResponse)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
    }

    private AuthResponse issueTokens(CurrentUser user) {
        String accessToken = jwtService.createAccessToken(user);
        String refreshToken = UUID.randomUUID().toString() + "." + UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at)
                VALUES (:id, :userId, :tokenHash, :expiresAt)
                """)
                .param("id", UUID.randomUUID().toString())
                .param("userId", user.id())
                .param("tokenHash", hash(refreshToken))
                .param("expiresAt", LocalDateTime.now().plusDays(refreshTokenTtlDays))
                .update();
        return new AuthResponse(accessToken, refreshToken, user.id(), user.username(), user.globalRole());
    }

    private String normalize(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String normalizeUsername(String username) {
        String normalized = normalize(username, "Username is required.");
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("Username must be at most 64 characters.");
        }
        return normalized;
    }

    private String normalizePassword(String password) {
        String normalized = normalize(password, "Password is required.");
        if (normalized.length() < 4) {
            throw new IllegalArgumentException("Password must be at least 4 characters.");
        }
        return normalized;
    }

    private String normalizeEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return null;
        }
        String normalized = email.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("Email must be at most 128 characters.");
        }
        return normalized;
    }

    private String normalizeGlobalRole(String role) {
        String normalized = StringUtils.hasText(role) ? role.trim().toUpperCase() : "VIEWER";
        if (!Set.of("ADMIN", "EDITOR", "VIEWER").contains(normalized)) {
            throw new IllegalArgumentException("Global role must be ADMIN, EDITOR, or VIEWER.");
        }
        return normalized;
    }

    private void requireAdmin() {
        CurrentUser currentUser = currentUserService.currentUser();
        if (!"ADMIN".equals(currentUser.globalRole())) {
            throw new AccessDeniedException("ADMIN access is required.");
        }
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash refresh token.", ex);
        }
    }

    private static AuthUser mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new AuthUser(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("global_role"));
    }

    private static UserResponse mapUserResponse(ResultSet rs, int rowNum) throws SQLException {
        return new UserResponse(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("global_role"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private record AuthUser(long id, String username, String passwordHash, String globalRole) {

        CurrentUser currentUser() {
            return new CurrentUser(id, username, globalRole);
        }
    }
}
