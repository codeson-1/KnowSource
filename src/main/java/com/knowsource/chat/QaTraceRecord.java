package com.knowsource.chat;

import java.util.List;

record QaTraceRecord(
        String id,
        long userId,
        String kbId,
        String query,
        String rewrittenQuery,
        List<SourceCitation> retrievedChunks,
        String answer,
        Integer retrievalMs,
        Integer llmMs,
        Integer rewriteLlmMs,
        Integer generationFirstTokenMs,
        Integer totalMs,
        String ragProfile) {
}
