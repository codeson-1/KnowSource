package com.knowsource.document;

public record DocumentChunkResponse(
        String id,
        String docId,
        int docVersion,
        String parentChunkId,
        String content,
        int chunkIndex,
        Integer pageNumber,
        String chunkType) {
}
