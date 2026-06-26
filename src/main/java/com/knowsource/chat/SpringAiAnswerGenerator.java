package com.knowsource.chat;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.knowsource.ai.AiProviderResilience;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnExpression("'${spring.ai.openai.api-key:}' != ''")
class SpringAiAnswerGenerator implements AnswerGenerator, StreamingAnswerGenerator {

    private static final String SYSTEM_PROMPT = """
            你是一个企业知识库问答助手。请严格基于给定上下文回答问题。
            如果上下文不足以回答，只能回答“知识库中未找到相关信息。”。
            每个关键事实后标注引用编号，例如 [1]。
            """;

    private final RestClient restClient;
    private final AiProviderResilience aiProviderResilience;
    private final String apiKey;
    private final String endpoint;
    private final String model;

    SpringAiAnswerGenerator(
            RestClient.Builder restClientBuilder,
            AiProviderResilience aiProviderResilience,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://dashscope.aliyuncs.com/compatible-mode}") String baseUrl,
            @Value("${spring.ai.openai.chat.completions-path:/v1/chat/completions}") String completionsPath,
            @Value("${spring.ai.openai.chat.options.model:qwen-plus}") String model) {
        this.restClient = restClientBuilder.build();
        this.aiProviderResilience = aiProviderResilience;
        this.apiKey = apiKey;
        this.endpoint = endpoint(baseUrl, completionsPath);
        this.model = model;
    }

    @Override
    public String generate(String question, List<SourceCitation> sources) {
        if (!StringUtils.hasText(apiKey)) {
            throw new com.knowsource.ai.AiProviderException(
                    "AI chat call failed.", new IllegalStateException("DashScope API key is not configured."));
        }
        ChatCompletionRequest request = new ChatCompletionRequest(
                model,
                List.of(
                        new ChatMessageRequest("system", SYSTEM_PROMPT),
                        new ChatMessageRequest("user", """
                                上下文：
                                %s

                                用户问题：%s
                                """.formatted(context(sources), question))));

        ChatCompletionResponse response = aiProviderResilience.executeChat(() -> restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .body(request)
                .retrieve()
                .body(ChatCompletionResponse.class));

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return "";
        }
        return response.choices().stream()
                .map(ChatChoice::message)
                .filter(Objects::nonNull)
                .map(ChatMessageResponse::content)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("");
    }

    @Override
    public void stream(String question, List<SourceCitation> sources, Consumer<String> tokenConsumer) {
        tokenConsumer.accept(generate(question, sources));
    }

    private static String context(List<SourceCitation> sources) {
        return sources.stream()
                .map(source -> "[%d] %s".formatted(source.index(), source.snippet()))
                .collect(Collectors.joining("\n"));
    }

    private static String endpoint(String baseUrl, String completionsPath) {
        String normalizedBase = stripTrailingSlash(baseUrl);
        String normalizedPath = completionsPath.startsWith("/") ? completionsPath : "/" + completionsPath;
        if (normalizedBase.endsWith("/v1") && normalizedPath.startsWith("/v1/")) {
            normalizedPath = normalizedPath.substring(3);
        }
        return normalizedBase + normalizedPath;
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private record ChatCompletionRequest(String model, List<ChatMessageRequest> messages) {
    }

    private record ChatMessageRequest(String role, String content) {
    }

    private record ChatCompletionResponse(List<ChatChoice> choices) {
    }

    private record ChatChoice(ChatMessageResponse message) {
    }

    private record ChatMessageResponse(String content) {
    }
}
