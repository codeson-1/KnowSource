package com.knowsource.chat;

import java.util.List;

public record ChatResponse(
        String qaTraceId,
        String kbId,
        String question,
        String ragProfile,
        String answer,
        boolean refused,
        List<SourceCitation> sources) {
}
