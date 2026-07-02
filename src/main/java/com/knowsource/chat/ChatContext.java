package com.knowsource.chat;

import java.util.List;

record ChatContext(
        String traceId,
        String sessionId,
        long userId,
        long startedAtNanos,
        String kbId,
        String question,
        String retrievalQuery,
        String rewrittenQuery,
        int rewriteMs,
        RagProfile ragProfile,
        String retrievalMode,
        boolean refused,
        List<SourceCitation> sources,
        String fallbackAnswer,
        int retrievalMs) {
}
