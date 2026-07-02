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
        double score,
        RetrievalSource retrievalSource,
        Integer vectorRank,
        Integer lexicalRank,
        Double vectorScore,
        Double lexicalScore,
        double fusionScore) {

    RetrievedChunk(
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
        this(
                chunkId,
                docId,
                docVersion,
                title,
                content,
                parentChunkId,
                chunkIndex,
                pageNumber,
                chunkType,
                distance,
                score,
                RetrievalSource.VECTOR,
                null,
                null,
                score,
                null,
                0.0d);
    }

    RetrievedChunk withVectorRank(int rank) {
        return new RetrievedChunk(
                chunkId,
                docId,
                docVersion,
                title,
                content,
                parentChunkId,
                chunkIndex,
                pageNumber,
                chunkType,
                distance,
                score,
                retrievalSource,
                rank,
                lexicalRank,
                vectorScore == null ? score : vectorScore,
                lexicalScore,
                fusionScore);
    }

    RetrievedChunk withLexicalRank(int rank) {
        return new RetrievedChunk(
                chunkId,
                docId,
                docVersion,
                title,
                content,
                parentChunkId,
                chunkIndex,
                pageNumber,
                chunkType,
                distance,
                score,
                retrievalSource,
                vectorRank,
                rank,
                vectorScore,
                lexicalScore == null ? score : lexicalScore,
                fusionScore);
    }

    RetrievedChunk merge(RetrievedChunk other) {
        RetrievalSource mergedSource = retrievalSource == other.retrievalSource
                ? retrievalSource
                : RetrievalSource.BOTH;
        return new RetrievedChunk(
                chunkId,
                docId,
                docVersion,
                title,
                content,
                parentChunkId,
                chunkIndex,
                pageNumber,
                chunkType,
                vectorScore != null ? distance : other.distance,
                Math.max(score, other.score),
                mergedSource,
                firstNonNull(vectorRank, other.vectorRank),
                firstNonNull(lexicalRank, other.lexicalRank),
                firstNonNull(vectorScore, other.vectorScore),
                firstNonNull(lexicalScore, other.lexicalScore),
                Math.max(fusionScore, other.fusionScore));
    }

    RetrievedChunk withFusionScore(double newFusionScore) {
        return new RetrievedChunk(
                chunkId,
                docId,
                docVersion,
                title,
                content,
                parentChunkId,
                chunkIndex,
                pageNumber,
                chunkType,
                distance,
                newFusionScore,
                retrievalSource,
                vectorRank,
                lexicalRank,
                vectorScore,
                lexicalScore,
                newFusionScore);
    }

    enum RetrievalSource {
        VECTOR,
        LEXICAL,
        BOTH
    }

    private static <T> T firstNonNull(T left, T right) {
        return left != null ? left : right;
    }
}
