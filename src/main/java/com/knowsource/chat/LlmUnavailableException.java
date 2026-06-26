package com.knowsource.chat;

/**
 * Thrown when the RAG pipeline has retrieved relevant context but the LLM answer
 * generator is not available (for example the model API key is not configured).
 *
 * <p>This is a hard failure rather than a silent placeholder answer: the MVP must not return a
 * "knowledge retrieved, LLM coming next" stub and pretend the core Q&A loop is closed.
 */
public class LlmUnavailableException extends RuntimeException {

    public static final int ERROR_CODE = 50002;

    public LlmUnavailableException(String message) {
        super(message);
    }
}
