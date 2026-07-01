package com.knowsource.chat;

import java.time.LocalDateTime;
import java.util.List;

public record ChatSessionMessageResponse(
        long id,
        String role,
        String content,
        String qaTraceId,
        List<SourceCitation> sources,
        LocalDateTime createdAt) {
}
