package com.knowsource.index;

public record IndexEvent(
        String id,
        String docId,
        String kbId,
        int docVersion,
        String eventType) {
}
