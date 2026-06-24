package com.knowsource.auth;

import java.time.LocalDateTime;

public record UserResponse(
        long id,
        String username,
        String email,
        String globalRole,
        LocalDateTime createdAt) {
}
