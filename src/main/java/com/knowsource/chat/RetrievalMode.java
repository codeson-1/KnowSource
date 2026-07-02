package com.knowsource.chat;

import java.util.Locale;

enum RetrievalMode {
    VECTOR("vector"),
    LEXICAL("lexical"),
    HYBRID("hybrid");

    private final String value;

    RetrievalMode(String value) {
        this.value = value;
    }

    String value() {
        return value;
    }

    static RetrievalMode from(String value) {
        if (value == null || value.isBlank()) {
            return HYBRID;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "vector" -> VECTOR;
            case "lexical" -> LEXICAL;
            case "hybrid" -> HYBRID;
            default -> throw new IllegalArgumentException("retrievalMode must be vector, lexical, or hybrid.");
        };
    }
}
