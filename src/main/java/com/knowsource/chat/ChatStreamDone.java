package com.knowsource.chat;

record ChatStreamDone(
        String qaTraceId,
        String sessionId,
        String kbId,
        String question,
        String rewrittenQuery,
        String ragProfile,
        boolean refused,
        String answer) {
}
