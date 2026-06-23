package com.knowsource.chat;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.knowsource.ai.AiProviderException;
import com.knowsource.ai.AiProviderResilience;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(prefix = "knowsource.rerank", name = "provider", havingValue = "dashscope", matchIfMissing = true)
class DashScopeRerankClient implements RerankProviderClient {

    private final RestClient restClient;
    private final AiProviderResilience aiProviderResilience;
    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final String instruct;
    private final Protocol protocol;

    DashScopeRerankClient(
            RestClient.Builder restClientBuilder,
            AiProviderResilience aiProviderResilience,
            @Value("${knowsource.rerank.dashscope.api-key:${AI_DASHSCOPE_API_KEY:}}") String apiKey,
            @Value("${knowsource.rerank.dashscope.endpoint:https://dashscope.aliyuncs.com/compatible-mode/v1/rerank}") String endpoint,
            @Value("${knowsource.rerank.dashscope.model:qwen3-rerank}") String model,
            @Value("${knowsource.rerank.dashscope.instruct:}") String instruct,
            @Value("${knowsource.rerank.dashscope.protocol:compatible}") String protocol) {
        this.restClient = restClientBuilder.build();
        this.aiProviderResilience = aiProviderResilience;
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.model = model;
        this.instruct = instruct;
        this.protocol = Protocol.fromConfig(protocol);
    }

    @Override
    public List<Integer> rerank(String question, List<String> documents, int topN) {
        if (!StringUtils.hasText(apiKey)) {
            throw new AiProviderException("AI rerank call failed.", new IllegalStateException("DashScope API key is not configured."));
        }
        if (documents.isEmpty()) {
            return List.of();
        }

        int normalizedTopN = Math.min(topN, documents.size());
        Object request = protocol == Protocol.COMPATIBLE
                ? compatibleRequest(question, documents, normalizedTopN)
                : dashScopeServiceRequest(question, documents, normalizedTopN);

        RerankResponse response = aiProviderResilience.executeRerank(() -> restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .body(request)
                .retrieve()
                .body(RerankResponse.class));

        List<RerankResult> results = response == null ? null : response.results();
        if (results == null) {
            results = response == null || response.output() == null ? null : response.output().results();
        }
        if (results == null) {
            return List.of();
        }

        return results.stream()
                .map(RerankResult::index)
                .filter(index -> index != null && index >= 0 && index < documents.size())
                .distinct()
                .limit(topN)
                .toList();
    }

    private CompatibleRerankRequest compatibleRequest(String question, List<String> documents, int topN) {
        return new CompatibleRerankRequest(
                model,
                question,
                documents,
                topN,
                StringUtils.hasText(instruct) ? instruct : null);
    }

    private DashScopeServiceRerankRequest dashScopeServiceRequest(String question, List<String> documents, int topN) {
        return new DashScopeServiceRerankRequest(
                model,
                new DashScopeServiceInput(question, documents),
                new DashScopeServiceParameters(topN, false));
    }

    private enum Protocol {
        COMPATIBLE,
        DASHSCOPE_SERVICE;

        private static Protocol fromConfig(String value) {
            if ("dashscope-service".equalsIgnoreCase(value)) {
                return DASHSCOPE_SERVICE;
            }
            return COMPATIBLE;
        }
    }

    private record CompatibleRerankRequest(
            String model,
            String query,
            List<String> documents,
            @JsonProperty("top_n") int topN,
            String instruct) {
    }

    private record DashScopeServiceRerankRequest(
            String model,
            DashScopeServiceInput input,
            DashScopeServiceParameters parameters) {
    }

    private record DashScopeServiceInput(String query, List<String> documents) {
    }

    private record DashScopeServiceParameters(
            @JsonProperty("top_n") int topN,
            @JsonProperty("return_documents") boolean returnDocuments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RerankResponse(List<RerankResult> results, DashScopeServiceOutput output) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DashScopeServiceOutput(List<RerankResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RerankResult(
            Integer index,
            @JsonProperty("relevance_score") Double relevanceScore) {
    }
}
