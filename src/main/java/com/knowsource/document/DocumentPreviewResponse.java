package com.knowsource.document;

public record DocumentPreviewResponse(
        String docId,
        String sourceKey,
        String previewUrl,
        Integer pageNumber) {
}
