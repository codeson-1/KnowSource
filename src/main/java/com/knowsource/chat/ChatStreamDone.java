package com.knowsource.chat;

record ChatStreamDone(String qaTraceId, String kbId, String question, boolean refused, String answer) {
}
