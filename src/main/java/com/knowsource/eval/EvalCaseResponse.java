package com.knowsource.eval;

import java.util.List;

public record EvalCaseResponse(
        String id,
        String setupQuestion,
        String question,
        String profile,
        String expected,
        boolean refused,
        List<String> sourceTitles,
        boolean documentHit,
        boolean citationHit,
        boolean refusalCorrect,
        boolean passed,
        String qaTraceId,
        String sessionId,
        boolean keywordHit,
        String matchedKeyword,
        int docRank,
        Double faithfulness) {
}
