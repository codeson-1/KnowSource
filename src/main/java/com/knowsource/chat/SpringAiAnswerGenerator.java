package com.knowsource.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowsource.ai.AiProviderException;
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
            如果上下文不足以回答，只能回答“知识库中未找到相关信息。”
            不要在正文中输出方括号引用编号；引用来源由前端证据面板展示。
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AiProviderResilience aiProviderResilience;
    private final String apiKey;
    private final String endpoint;
    private final String model;

    SpringAiAnswerGenerator(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            AiProviderResilience aiProviderResilience,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://dashscope.aliyuncs.com/compatible-mode}") String baseUrl,
            @Value("${spring.ai.openai.chat.completions-path:/v1/chat/completions}") String completionsPath,
            @Value("${spring.ai.openai.chat.options.model:qwen-plus}") String model) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.aiProviderResilience = aiProviderResilience;
        this.apiKey = apiKey;
        this.endpoint = endpoint(baseUrl, completionsPath);
        this.model = model;
    }

    @Override
    public String generate(String question, List<SourceCitation> sources) {
        requireApiKey();
        ChatCompletionRequest request = request(question, sources, false);

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
        requireApiKey();
        ChatCompletionRequest request = request(question, sources, true);

        aiProviderResilience.executeChat(() -> restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_NDJSON, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .body(request)
                .exchange((ignoredRequest, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw new IllegalStateException("Chat stream failed with HTTP " + response.getStatusCode());
                    }
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            response.getBody(), StandardCharsets.UTF_8))) {
                        streamTokens(reader, tokenConsumer);
                    } catch (IOException ex) {
                        throw new IllegalStateException("Failed to read chat stream.", ex);
                    }
                    return null;
                }));
    }

    private void requireApiKey() {
        if (!StringUtils.hasText(apiKey)) {
            throw new AiProviderException(
                    "AI chat call failed.", new IllegalStateException("DashScope API key is not configured."));
        }
    }

    private ChatCompletionRequest request(String question, List<SourceCitation> sources, boolean stream) {
        return new ChatCompletionRequest(
                model,
                List.of(
                        new ChatMessageRequest("system", SYSTEM_PROMPT),
                        new ChatMessageRequest("user", """
                                上下文：
                                %s

                                用户问题：%s
                                """.formatted(context(sources), question))),
                stream);
    }

    private void streamTokens(BufferedReader reader, Consumer<String> tokenConsumer) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            String payload = normalizeStreamPayload(line);
            if (!StringUtils.hasText(payload)) {
                continue;
            }
            if ("[DONE]".equals(payload)) {
                return;
            }
            String token = tokenFromStreamPayload(payload);
            if (!token.isEmpty()) {
                tokenConsumer.accept(token);
            }
        }
    }

    private static String normalizeStreamPayload(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith(":")) {
            return "";
        }
        return trimmed.startsWith("data:") ? trimmed.substring(5).trim() : trimmed;
    }

    private String tokenFromStreamPayload(String payload) throws IOException {
        JsonNode root = objectMapper.readTree(payload);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }
        JsonNode choice = choices.get(0);
        JsonNode deltaContent = choice.path("delta").path("content");
        if (!deltaContent.isMissingNode() && !deltaContent.isNull()) {
            return deltaContent.asText("");
        }
        return choice.path("message").path("content").asText("");
    }

    private static String context(List<SourceCitation> sources) {
        return sources.stream()
                .map(source -> "来源 %d：%s".formatted(source.index(), source.snippet()))
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

    private record ChatCompletionRequest(String model, List<ChatMessageRequest> messages, boolean stream) {
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
