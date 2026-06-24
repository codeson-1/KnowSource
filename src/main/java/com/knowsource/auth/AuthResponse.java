package com.knowsource.auth;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        long userId,
        String username,
        String globalRole) {
}
