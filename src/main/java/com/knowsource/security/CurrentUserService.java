package com.knowsource.security;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

import com.knowsource.cache.CacheKeys;
import com.knowsource.cache.CacheService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {

    private final JdbcClient jdbcClient;
    private final CacheService cacheService;
    private final boolean cacheEnabled;
    private final long ttlSeconds;

    public CurrentUserService(
            JdbcClient jdbcClient,
            CacheService cacheService,
            @Value("${knowsource.cache.user-cache-enabled:true}") boolean cacheEnabled,
            @Value("${knowsource.cache.user-ttl-seconds:30}") long ttlSeconds) {
        this.jdbcClient = jdbcClient;
        this.cacheService = cacheService;
        this.cacheEnabled = cacheEnabled;
        this.ttlSeconds = ttlSeconds;
    }

    public CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required.");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CurrentUserPrincipal currentUserPrincipal) {
            return currentUserPrincipal.currentUser();
        }
        String username = authentication.getName();
        return findByUsername(username);
    }

    public long currentUserId() {
        return currentUser().id();
    }

    public CurrentUser findByUsername(String username) {
        if (cacheEnabled) {
            Optional<CurrentUser> cached = cacheService.get(CacheKeys.user(username), CurrentUser.class);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        CurrentUser user = jdbcClient.sql("""
                SELECT id, username, global_role, token_version
                FROM users
                WHERE username = :username
                """)
                .param("username", username)
                .query(CurrentUserService::mapUser)
                .optional()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Authenticated user not found."));
        // 不缓存负值（用户不存在时抛异常，不到这里），避免缓存不存在用户名。
        if (cacheEnabled) {
            cacheService.put(CacheKeys.user(username), user, Duration.ofSeconds(ttlSeconds));
        }
        return user;
    }

    private static CurrentUser mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new CurrentUser(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("global_role"),
                rs.getInt("token_version"));
    }
}
