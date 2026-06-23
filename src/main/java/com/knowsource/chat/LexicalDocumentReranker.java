package com.knowsource.chat;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
class LexicalDocumentReranker implements DocumentReranker {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHan}]+");

    @Override
    public List<RetrievedChunk> rerank(String question, List<RetrievedChunk> chunks, int topK) {
        Set<String> queryTerms = tokenize(question);
        if (queryTerms.isEmpty()) {
            return chunks.stream()
                    .limit(topK)
                    .toList();
        }

        return chunks.stream()
                .map(chunk -> new ScoredChunk(chunk, lexicalScore(queryTerms, chunk.content())))
                .sorted(Comparator
                        .comparingDouble(ScoredChunk::lexicalScore)
                        .thenComparingDouble(scored -> scored.chunk().score())
                        .reversed())
                .limit(topK)
                .map(ScoredChunk::chunk)
                .toList();
    }

    private static double lexicalScore(Set<String> queryTerms, String content) {
        Set<String> contentTerms = tokenize(content);
        if (contentTerms.isEmpty()) {
            return 0.0d;
        }

        long matches = queryTerms.stream()
                .filter(contentTerms::contains)
                .count();
        return (double) matches / queryTerms.size();
    }

    private static Set<String> tokenize(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        return terms;
    }

    private record ScoredChunk(RetrievedChunk chunk, double lexicalScore) {
    }
}
