package com.knowsource.chat;

import java.time.LocalDateTime;

public record ChatSessionSummaryResponse(
        String id,
        String kbId,
        String title,
        int messageCount,
        String lastMessageRole,
        String lastMessagePreview,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
