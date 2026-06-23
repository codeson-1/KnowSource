package com.knowsource.index;

import java.util.List;

import com.knowsource.ai.AiProviderResilience;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(EmbeddingModel.class)
public class SpringAiDocumentEmbeddingGateway implements DocumentEmbeddingGateway {

    private final EmbeddingModel embeddingModel;
    private final AiProviderResilience aiProviderResilience;

    public SpringAiDocumentEmbeddingGateway(EmbeddingModel embeddingModel, AiProviderResilience aiProviderResilience) {
        this.embeddingModel = embeddingModel;
        this.aiProviderResilience = aiProviderResilience;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return aiProviderResilience.executeEmbedding(() -> embeddingModel.embed(texts));
    }
}
