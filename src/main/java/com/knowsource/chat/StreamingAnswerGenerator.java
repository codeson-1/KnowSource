package com.knowsource.chat;

import java.util.List;
import java.util.function.Consumer;

interface StreamingAnswerGenerator {

    void stream(String question, List<SourceCitation> sources, Consumer<String> tokenConsumer);
}
