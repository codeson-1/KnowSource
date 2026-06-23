package com.knowsource.chat;

import java.util.List;

interface RerankProviderClient {

    List<Integer> rerank(String question, List<String> documents, int topN);
}
