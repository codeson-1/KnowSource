package com.knowsource.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.knowsource.ai.AiProviderException;
import com.knowsource.ai.AiProviderResilience;
import com.knowsource.cache.EmbeddingCache;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<EmbeddingCache> embeddingCacheProvider;
    private final String apiKey;
    private final String endpoint;
    private final String model;

    public DashScopeEmbeddingGateway(
            RestClient.Builder restClientBuilder,
            AiProviderResilience aiProviderResilience,
            ObjectProvider<EmbeddingCache> embeddingCacheProvider,
            @Value("${knowsource.embedding.dashscope.api-key:}") String dashScopeApiKey,
            @Value("${spring.ai.openai.api-key:}") String springAiOpenAiApiKey,
            @Value("${AI_DASHSCOPE_API_KEY:}") String envApiKey,
            @Value("${knowsource.embedding.dashscope.endpoint:https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings}") String endpoint,
            @Value("${knowsource.embedding.dashscope.model:text-embedding-v3}") String model) {
        this.restClient = restClientBuilder.build();
        this.aiProviderResilience = aiProviderResilience;
        this.embeddingCacheProvider = embeddingCacheProvider;
        this.apiKey = firstText(dashScopeApiKey, springAiOpenAiApiKey, envApiKey);
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
        // 仅 query 路径走缓存（文档入库不缓存，重复 embed 无意义）
        EmbeddingCache cache = embeddingCacheProvider.getIfAvailable();
        if (cache != null && cache.isEnabled()) {
            String queryHash = sha256(text);
            java.util.Optional<float[]> cached = cache.get(queryHash);
            if (cached.isPresent()) {
                return List.of(cached.get());
            }
            List<float[]> fresh = embed(List.of(text), TEXT_TYPE_QUERY);
            if (!fresh.isEmpty()) {
                cache.put(queryHash, fresh.getFirst());
            }
            return fresh;
        }
        return embed(List.of(text), TEXT_TYPE_QUERY);
    }

    /** SHA-256(query) → 64 字符 hex，作为缓存 key（避免长 query 占内存） */
    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            // JDK 必有 SHA-256，理论上不会抛
            throw new IllegalStateException("SHA-256 not available", ex);
        }
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
                .sorted(java.util.Comparator.comparingInt(EmbeddingData::sortIndex))
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

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
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
    private record EmbeddingData(
            Integer index,
            @JsonProperty("text_index") Integer textIndex,
            List<Double> embedding) {

        private int sortIndex() {
            if (textIndex != null) {
                return textIndex;
            }
            if (index != null) {
                return index;
            }
            return 0;
        }
    }
}
