package com.knowsource.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import com.knowsource.ai.AiProviderResilience;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DashScopeEmbeddingGatewayTest {

    @Test
    void documentEmbeddingUsesDocumentTextType() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DashScopeEmbeddingGateway gateway = gateway(builder);

        server.expect(requestTo("https://dashscope.example/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(content().json("""
                        {
                          "model": "text-embedding-v3",
                          "input": ["policy text"],
                          "encoding_format": "float",
                          "text_type": "document"
                        }
                        """))
                .andRespond(withSuccess(responseJson(), MediaType.APPLICATION_JSON));

        List<float[]> embeddings = gateway.embedDocuments(List.of("policy text"));

        assertThat(embeddings).hasSize(1);
        assertThat(embeddings.getFirst()).containsExactly(0.1f, 0.2f);
        server.verify();
    }

    @Test
    void queryEmbeddingUsesQueryTextType() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DashScopeEmbeddingGateway gateway = gateway(builder);

        server.expect(requestTo("https://dashscope.example/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "model": "text-embedding-v3",
                          "input": ["annual leave"],
                          "encoding_format": "float",
                          "text_type": "query"
                        }
                        """))
                .andRespond(withSuccess(responseJson(), MediaType.APPLICATION_JSON));

        List<float[]> embeddings = gateway.embedQuery("annual leave");

        assertThat(embeddings).hasSize(1);
        assertThat(embeddings.getFirst()).containsExactly(0.1f, 0.2f);
        server.verify();
    }

    @Test
    void responseCanUseDashScopeTextIndexField() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DashScopeEmbeddingGateway gateway = gateway(builder);

        server.expect(requestTo("https://dashscope.example/v1/embeddings"))
                .andRespond(withSuccess("""
                        {
                          "data": [
                            {"text_index": 1, "embedding": [0.3, 0.4]},
                            {"text_index": 0, "embedding": [0.1, 0.2]}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<float[]> embeddings = gateway.embedDocuments(List.of("first", "second"));

        assertThat(embeddings).hasSize(2);
        assertThat(embeddings.get(0)).containsExactly(0.1f, 0.2f);
        assertThat(embeddings.get(1)).containsExactly(0.3f, 0.4f);
        server.verify();
    }

    @Test
    void apiKeyCanFallbackToSpringAiOpenAiKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DashScopeEmbeddingGateway gateway = new DashScopeEmbeddingGateway(
                builder,
                resilience(),
                "",
                "spring-ai-key",
                "",
                "https://dashscope.example/v1/embeddings",
                "text-embedding-v3");

        server.expect(requestTo("https://dashscope.example/v1/embeddings"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer spring-ai-key"))
                .andRespond(withSuccess(responseJson(), MediaType.APPLICATION_JSON));

        List<float[]> embeddings = gateway.embedDocuments(List.of("policy text"));

        assertThat(embeddings).hasSize(1);
        server.verify();
    }

    private static DashScopeEmbeddingGateway gateway(RestClient.Builder builder) {
        return new DashScopeEmbeddingGateway(
                builder,
                resilience(),
                "test-key",
                "",
                "",
                "https://dashscope.example/v1/embeddings",
                "text-embedding-v3");
    }

    private static String responseJson() {
        return """
                {
                  "data": [
                    {"index": 0, "embedding": [0.1, 0.2]}
                  ]
                }
                """;
    }

    private static AiProviderResilience resilience() {
        return new AiProviderResilience(
                10, 1, 0, 10, 0,
                10, 1, 0, 10, 0,
                1, 1,
                10, 1, 0, 10, 0);
    }
}
