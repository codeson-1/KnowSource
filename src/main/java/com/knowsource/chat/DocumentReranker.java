package com.knowsource.chat;

import java.util.List;

interface DocumentReranker {

    List<RetrievedChunk> rerank(String question, List<RetrievedChunk> chunks, int topK);
}
