package com.knowsource.chat;

import java.util.List;

record ChatSessionHistory(String sessionId, boolean newlyCreated, List<ChatMessage> messages) {

    boolean hasMessages() {
        return !messages.isEmpty();
    }
}
