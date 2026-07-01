package com.knowsource.chat;

import java.time.LocalDateTime;
import java.util.List;

public record ChatSessionDetailResponse(
        String id,
        String kbId,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ChatSessionMessageResponse> messages) {
}
