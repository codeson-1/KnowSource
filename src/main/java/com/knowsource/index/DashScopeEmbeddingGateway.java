package com.knowsource.index;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.knowsource.ai.AiProviderException;
import com.knowsource.ai.AiProviderResilience;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@Primary
@ConditionalOnProperty(prefix = "knowsource.embedding.dashscope", name = "enabled", havingValue = "true")
public class DashScopeEmbeddingGateway implements DocumentEmbeddingGateway {

    private static final String TEXT_TYPE_DOCUMENT = "document";
    private static final String TEXT_TYPE_QUERY = "query";

    private final RestClient restClient;
    private final AiProviderResilience aiProviderResilience;
    private final String apiKey;
    private final String endpoint;
    private final String model;

    public DashScopeEmbeddingGateway(
            RestClient.Builder restClientBuilder,
            AiProviderResilience aiProviderResilience,
            @Value("${knowsource.embedding.dashscope.api-key:${AI_DASHSCOPE_API_KEY:}}") String apiKey,
            @Value("${knowsource.embedding.dashscope.endpoint:https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings}") String endpoint,
            @Value("${knowsource.embedding.dashscope.model:text-embedding-v3}") String model) {
        this.restClient = restClientBuilder.build();
        this.aiProviderResilience = aiProviderResilience;
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.model = model;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return embedDocuments(texts);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return embed(texts, TEXT_TYPE_DOCUMENT);
    }

    @Override
    public List<float[]> embedQuery(String text) {
        return embed(List.of(text), TEXT_TYPE_QUERY);
    }

    private List<float[]> embed(List<String> texts, String textType) {
        if (!StringUtils.hasText(apiKey)) {
            throw new AiProviderException("AI embedding call failed.", new IllegalStateException("DashScope API key is not configured."));
        }
        if (texts.isEmpty()) {
            return List.of();
        }

        EmbeddingRequest request = new EmbeddingRequest(model, texts, "float", textType);
        EmbeddingResponse response = aiProviderResilience.executeEmbedding(() -> restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .body(request)
                .retrieve()
                .body(EmbeddingResponse.class));

        if (response == null || response.data() == null) {
            return List.of();
        }

        return response.data().stream()
                .sorted(java.util.Comparator.comparingInt(EmbeddingData::index))
                .map(EmbeddingData::embedding)
                .map(DashScopeEmbeddingGateway::toFloatArray)
                .toList();
    }

    private static float[] toFloatArray(List<Double> values) {
        float[] embedding = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            embedding[i] = values.get(i).floatValue();
        }
        return embedding;
    }

    private record EmbeddingRequest(
            String model,
            List<String> input,
            @JsonProperty("encoding_format") String encodingFormat,
            @JsonProperty("text_type") String textType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingResponse(List<EmbeddingData> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingData(int index, List<Double> embedding) {
    }
}
