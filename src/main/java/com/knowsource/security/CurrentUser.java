package com.knowsource.security;

public record CurrentUser(
        long id,
        String username,
        String globalRole) {
}
