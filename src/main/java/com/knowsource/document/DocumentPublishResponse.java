package com.knowsource.document;

public record DocumentPublishResponse(
        String docId,
        String kbId,
        int version,
        String indexStatus,
        String eventId,
        String message) {
}
