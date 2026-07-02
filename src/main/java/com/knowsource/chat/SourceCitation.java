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
        double score,
        String retrievalSource,
        Integer vectorRank,
        Integer lexicalRank,
        Double vectorScore,
        Double lexicalScore,
        double fusionScore) {
}
