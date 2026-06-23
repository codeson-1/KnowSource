package com.knowsource.chat;

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

class DashScopeRerankClientTest {

    @Test
    void callsCompatibleRerankEndpointAndReturnsIndexes() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DashScopeRerankClient client = new DashScopeRerankClient(
                builder,
                resilience(),
                "test-key",
                "https://dashscope.example/v1/rerank",
                "qwen3-rerank",
                "",
                "compatible");

        server.expect(requestTo("https://dashscope.example/v1/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(content().json("""
                        {
                          "model": "qwen3-rerank",
                          "query": "annual leave",
                          "documents": ["security", "annual leave"],
                          "top_n": 2
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "results": [
                            {"index": 1, "relevance_score": 0.98},
                            {"index": 0, "relevance_score": 0.12}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<Integer> indexes = client.rerank("annual leave", List.of("security", "annual leave"), 2);

        assertThat(indexes).containsExactly(1, 0);
        server.verify();
    }

    @Test
    void parsesDashScopeServiceResponseShape() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DashScopeRerankClient client = new DashScopeRerankClient(
                builder,
                resilience(),
                "test-key",
                "https://dashscope.example/api/v1/services/rerank/text-rerank/text-rerank",
                "qwen3-rerank",
                "",
                "dashscope-service");

        server.expect(requestTo("https://dashscope.example/api/v1/services/rerank/text-rerank/text-rerank"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "model": "qwen3-rerank",
                          "input": {
                            "query": "annual leave",
                            "documents": ["security", "annual leave"]
                          },
                          "parameters": {
                            "top_n": 1,
                            "return_documents": false
                          }
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "output": {
                            "results": [
                              {"index": 1, "relevance_score": 0.98}
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        List<Integer> indexes = client.rerank("annual leave", List.of("security", "annual leave"), 1);

        assertThat(indexes).containsExactly(1);
        server.verify();
    }

    private static AiProviderResilience resilience() {
        return new AiProviderResilience(
                10, 1, 0, 10, 0,
                10, 1, 0, 10, 0,
                1, 1,
                10, 1, 0, 10, 0);
    }
}
