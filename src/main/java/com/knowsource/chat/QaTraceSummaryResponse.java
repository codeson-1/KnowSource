package com.knowsource.chat;

import java.time.LocalDateTime;

public record QaTraceSummaryResponse(
        String id,
        String kbId,
        String query,
        String answerPreview,
        int sourceCount,
        Integer totalMs,
        String ragProfile,
        LocalDateTime createdAt) {
}
