package com.knowsource.kb;

import java.time.LocalDateTime;

public record KnowledgeBaseResponse(
        String id,
        String name,
        String description,
        long ownerId,
        LocalDateTime createdAt,
        String memberRole) {
}
