package com.knowsource.index;

public record ChunkForIndex(
        String id,
        String docId,
        int docVersion,
        String parentChunkId,
        String content,
        int chunkIndex,
        Integer pageNumber,
        String chunkType) {
}
