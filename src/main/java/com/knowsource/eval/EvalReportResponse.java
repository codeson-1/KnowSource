package com.knowsource.eval;

import java.time.LocalDateTime;

public record EvalReportResponse(
        LocalDateTime generatedAt,
        String reportPath,
        String markdown) {
}
