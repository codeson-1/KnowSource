package com.knowsource.security;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {

    private final JdbcClient jdbcClient;

    public CurrentUserService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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
        return jdbcClient.sql("""
                SELECT id, username, global_role
                FROM users
                WHERE username = :username
                """)
                .param("username", username)
                .query(CurrentUserService::mapUser)
                .optional()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Authenticated user not found."));
    }

    private static CurrentUser mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new CurrentUser(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("global_role"));
    }
}
