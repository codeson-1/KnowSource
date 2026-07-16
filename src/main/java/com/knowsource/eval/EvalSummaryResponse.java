package com.knowsource.eval;

public record EvalSummaryResponse(
        int totalCases,
        int inScopeCases,
        int outOfScopeCases,
        double documentHitRate,
        double citationHitRate,
        double refusalAccuracy,
        double keywordHitRate,
        double mrr,
        double meanDocRank,
        Double faithfulness) {
}
