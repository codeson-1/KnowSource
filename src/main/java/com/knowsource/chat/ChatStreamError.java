package com.knowsource.chat;

/**
 * SSE error event payload sent when the chat stream cannot produce an LLM answer
 * (for example the model API key is not configured).
 *
 * @param code     machine-readable error code (see {@link LlmUnavailableException#ERROR_CODE})
 * @param message  human-readable message
 * @param qaTraceId QA trace id so the client can correlate the failed request
 */
record ChatStreamError(int code, String message, String qaTraceId) {
}
