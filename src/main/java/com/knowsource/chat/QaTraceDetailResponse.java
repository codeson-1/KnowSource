package com.knowsource.chat;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

public record QaTraceDetailResponse(
        String id,
        String sessionId,
        String kbId,
        long userId,
        String query,
        String rewrittenQuery,
        List<SourceCitation> retrievedChunks,
        String answer,
        Integer retrievalMs,
        Integer llmMs,
        Integer rewriteLlmMs,
        Integer generationFirstTokenMs,
        Integer totalMs,
        JsonNode tokenUsage,
        String ragProfile,
        LocalDateTime createdAt) {
}
