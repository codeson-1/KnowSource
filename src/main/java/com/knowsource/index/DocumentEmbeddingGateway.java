package com.knowsource.index;

import java.util.List;

public interface DocumentEmbeddingGateway {

    List<float[]> embed(List<String> texts);

    default List<float[]> embedDocuments(List<String> texts) {
        return embed(texts);
    }

    default List<float[]> embedQuery(String text) {
        return embed(List.of(text));
    }
}
