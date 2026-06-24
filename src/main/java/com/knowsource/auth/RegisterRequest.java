package com.knowsource.auth;

public record RegisterRequest(
        String username,
        String password,
        String email) {
}
