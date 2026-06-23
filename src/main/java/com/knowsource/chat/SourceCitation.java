package com.knowsource.chat;

public record SourceCitation(
        int index,
        String chunkId,
        String docId,
        int docVersion,
        String title,
        int chunkIndex,
        Integer pageNumber,
        String snippet,
        double score) {
}
