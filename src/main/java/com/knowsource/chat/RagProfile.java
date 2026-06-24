package com.knowsource.chat;

import java.util.Locale;

import org.springframework.util.StringUtils;

enum RagProfile {
    AUTO("auto"),
    NAIVE("naive"),
    MODULAR("modular");

    private final String value;

    RagProfile(String value) {
        this.value = value;
    }

    String value() {
        return value;
    }

    static RagProfile fromRequest(String value) {
        if (!StringUtils.hasText(value)) {
            return AUTO;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (RagProfile profile : values()) {
            if (profile.value.equals(normalized)) {
                return profile;
            }
        }

        throw new IllegalArgumentException("profile must be auto, naive, or modular.");
    }
}
