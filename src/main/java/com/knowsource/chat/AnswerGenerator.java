package com.knowsource.chat;

import java.util.List;

interface AnswerGenerator {

    String generate(String question, List<SourceCitation> sources);
}
