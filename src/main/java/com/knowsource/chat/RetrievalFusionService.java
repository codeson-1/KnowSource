package com.knowsource.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
class RetrievalFusionService {

    List<RetrievedChunk> fuse(List<RetrievedChunk> vectorCandidates, List<RetrievedChunk> lexicalCandidates, int rrfK) {
        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
        for (int i = 0; i < vectorCandidates.size(); i++) {
            RetrievedChunk chunk = vectorCandidates.get(i);
            RetrievedChunk ranked = chunk.vectorRank() == null ? chunk.withVectorRank(i + 1) : chunk;
            merged.merge(chunkKey(ranked), ranked, RetrievedChunk::merge);
        }
        for (int i = 0; i < lexicalCandidates.size(); i++) {
            RetrievedChunk chunk = lexicalCandidates.get(i);
            RetrievedChunk ranked = chunk.lexicalRank() == null ? chunk.withLexicalRank(i + 1) : chunk;
            merged.merge(chunkKey(ranked), ranked, RetrievedChunk::merge);
        }

        return merged.values().stream()
                .map(chunk -> chunk.withFusionScore(rrfScore(chunk, rrfK)))
                .sorted(Comparator
                        .comparingDouble(RetrievedChunk::fusionScore).reversed()
                        .thenComparing(chunk -> chunk.vectorRank() == null ? Integer.MAX_VALUE : chunk.vectorRank())
                        .thenComparing(chunk -> chunk.lexicalRank() == null ? Integer.MAX_VALUE : chunk.lexicalRank()))
                .toList();
    }

    List<RetrievedChunk> mergeAcrossQueries(List<List<RetrievedChunk>> perQueryResults) {
        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
        for (List<RetrievedChunk> results : perQueryResults) {
            for (RetrievedChunk chunk : results) {
                merged.merge(chunkKey(chunk), chunk, RetrievalFusionService::higherFusionScore);
            }
        }
        List<RetrievedChunk> chunks = new ArrayList<>(merged.values());
        chunks.sort(Comparator.comparingDouble(RetrievedChunk::fusionScore).reversed());
        return chunks;
    }

    static String chunkKey(RetrievedChunk chunk) {
        return chunk.docId() + ":" + chunk.docVersion() + ":" + chunk.chunkId();
    }

    private static double rrfScore(RetrievedChunk chunk, int rrfK) {
        double score = 0.0d;
        if (chunk.vectorRank() != null) {
            score += 1.0d / (rrfK + chunk.vectorRank());
        }
        if (chunk.lexicalRank() != null) {
            score += 1.0d / (rrfK + chunk.lexicalRank());
        }
        return score;
    }

    private static RetrievedChunk higherFusionScore(RetrievedChunk left, RetrievedChunk right) {
        return left.fusionScore() >= right.fusionScore() ? left : right;
    }
}
