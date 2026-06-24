package com.knowsource.chat;

import java.util.List;

public record ChatResponse(
        String qaTraceId,
        String sessionId,
        String kbId,
        String question,
        String rewrittenQuery,
        String ragProfile,
        String answer,
        boolean refused,
        List<SourceCitation> sources) {
}
