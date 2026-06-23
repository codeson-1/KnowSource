package com.knowsource.chat;

record ChatStreamDone(String qaTraceId, String kbId, String question, String ragProfile, boolean refused, String answer) {
}
