package com.knowsource.chat;

import java.util.List;

record QueryRewriteResult(String query, String rewrittenQuery, List<String> retrievalQueries, int rewriteMs) {
}
