package com.knowsource.eval;

import java.time.LocalDateTime;
import java.util.List;

public record EvalRunResponse(
        String kbId,
        LocalDateTime generatedAt,
        EvalSummaryResponse summary,
        List<EvalCaseResponse> cases,
        String reportPath) {
}
