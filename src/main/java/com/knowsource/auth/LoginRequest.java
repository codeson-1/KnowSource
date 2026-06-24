package com.knowsource.auth;

public record LoginRequest(
        String username,
        String password) {
}
