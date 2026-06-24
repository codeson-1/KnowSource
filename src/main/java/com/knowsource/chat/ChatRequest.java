package com.knowsource.chat;

public record ChatRequest(String question, Integer topK, String profile, String sessionId) {
}
