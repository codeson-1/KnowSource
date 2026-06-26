package com.knowsource.eval;

public record EvalSummaryResponse(
        int totalCases,
        int inScopeCases,
        int outOfScopeCases,
        double recallAt5,
        double citationHitRate,
        double refusalAccuracy) {
}
