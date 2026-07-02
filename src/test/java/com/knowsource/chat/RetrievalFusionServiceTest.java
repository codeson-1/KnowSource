package com.knowsource.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class RetrievalFusionServiceTest {

    private final RetrievalFusionService fusionService = new RetrievalFusionService();

    @Test
    void boostsChunksFoundByBothRetrievalBranches() {
        RetrievedChunk vectorTop = chunk("a", RetrievedChunk.RetrievalSource.VECTOR, 0.9d);
        RetrievedChunk vectorSecond = chunk("b", RetrievedChunk.RetrievalSource.VECTOR, 0.8d);
        RetrievedChunk lexicalTop = chunk("b", RetrievedChunk.RetrievalSource.LEXICAL, 0.3d);

        List<RetrievedChunk> fused = fusionService.fuse(
                List.of(vectorTop, vectorSecond),
                List.of(lexicalTop),
                60);

        assertThat(fused).extracting(RetrievedChunk::chunkId).containsExactly("b", "a");
        assertThat(fused.getFirst().retrievalSource()).isEqualTo(RetrievedChunk.RetrievalSource.BOTH);
        assertThat(fused.getFirst().vectorRank()).isEqualTo(2);
        assertThat(fused.getFirst().lexicalRank()).isEqualTo(1);
        assertThat(fused.getFirst().fusionScore()).isGreaterThan(fused.get(1).fusionScore());
    }

    @Test
    void keepsSingleBranchCandidatesWhenTheOtherBranchIsEmpty() {
        List<RetrievedChunk> fused = fusionService.fuse(
                List.of(chunk("a", RetrievedChunk.RetrievalSource.VECTOR, 0.9d)),
                List.of(),
                60);

        assertThat(fused).hasSize(1);
        assertThat(fused.getFirst().retrievalSource()).isEqualTo(RetrievedChunk.RetrievalSource.VECTOR);
        assertThat(fused.getFirst().vectorRank()).isEqualTo(1);
        assertThat(fused.getFirst().lexicalRank()).isNull();
    }

    private static RetrievedChunk chunk(String chunkId, RetrievedChunk.RetrievalSource source, double score) {
        return new RetrievedChunk(
                chunkId,
                "doc-" + chunkId,
                1,
                "Document " + chunkId,
                "content " + chunkId,
                "parent-" + chunkId,
                0,
                null,
                "TEXT",
                1.0d - score,
                score,
                source,
                null,
                null,
                source == RetrievedChunk.RetrievalSource.VECTOR ? score : null,
                source == RetrievedChunk.RetrievalSource.LEXICAL ? score : null,
                0.0d);
    }
}
