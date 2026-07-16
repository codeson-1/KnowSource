package com.knowsource.eval;

import java.util.List;

public record GoldenCase(
        String id,
        String setupQuestion,
        String question,
        String profile,
        String expectedDocTitle,
        List<String> expectedKeywords,
        boolean outOfScope) {
}
