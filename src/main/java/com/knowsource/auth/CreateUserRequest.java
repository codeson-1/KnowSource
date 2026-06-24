package com.knowsource.auth;

public record CreateUserRequest(
        String username,
        String password,
        String email,
        String globalRole) {
}
