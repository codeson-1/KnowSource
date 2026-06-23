package com.knowsource.document;

public record StoredSource(
        String sourceKey,
        String originalFilename,
        String contentType,
        long sizeBytes) {
}
