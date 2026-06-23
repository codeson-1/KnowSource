package com.knowsource.index;

import java.util.List;

public interface DocumentEmbeddingGateway {

    List<float[]> embed(List<String> texts);
}
