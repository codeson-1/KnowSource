package com.knowsource.document;

import java.time.LocalDateTime;

public record DocumentResponse(
        String id,
        String kbId,
        String title,
        String status,
        String indexStatus,
        String ossKey,
        int version,
        String fileType,
        long createdBy,
        LocalDateTime publishedAt,
        LocalDateTime vectorsSyncedAt,
        LocalDateTime createdAt,
        String latestIngestTaskId,
        String latestIngestStatus,
        int parentChunkCount,
        int childChunkCount,
        String latestFailedIndexEventId) {
}
