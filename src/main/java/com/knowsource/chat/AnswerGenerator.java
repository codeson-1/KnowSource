package com.knowsource.chat;

import java.util.List;

public interface AnswerGenerator {

    String generate(String question, List<SourceCitation> sources);
}
