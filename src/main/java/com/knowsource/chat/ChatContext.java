package com.knowsource.chat;

import java.util.List;

record ChatContext(
        String traceId,
        long userId,
        long startedAtNanos,
        String kbId,
        String question,
        boolean refused,
        List<SourceCitation> sources,
        String fallbackAnswer,
        int retrievalMs) {
}
