package com.knowsource.chat;

record RetrievedChunk(
        String chunkId,
        String docId,
        int docVersion,
        String title,
        String content,
        String parentChunkId,
        int chunkIndex,
        Integer pageNumber,
        String chunkType,
        double distance,
        double score) {
}
