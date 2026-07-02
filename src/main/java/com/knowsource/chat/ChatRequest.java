package com.knowsource.chat;

public record ChatRequest(String question, Integer topK, String profile, String sessionId, String retrievalMode) {

    public ChatRequest(String question, Integer topK, String profile, String sessionId) {
        this(question, topK, profile, sessionId, null);
    }
}
