package com.knowsource.eval;

import java.time.LocalDateTime;

public record EvalHistoryItem(
        LocalDateTime generatedAt,
        String reportPath,
        int totalCases,
        int inScopeCases,
        int outOfScopeCases,
        double documentHitRate,
        double citationHitRate,
        double refusalAccuracy,
        Double faithfulness) {
}
