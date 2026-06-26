package com.knowsource.web;

import java.time.Instant;

public record ApiErrorResponse(
        int code,
        String message,
        Instant timestamp) {
}
