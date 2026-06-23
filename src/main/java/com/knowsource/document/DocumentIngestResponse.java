package com.knowsource.document;

public record DocumentIngestResponse(
        DocumentResponse document,
        String ingestTaskId,
        String ingestStatus,
        int parentChunkCount,
        int childChunkCount) {
}
