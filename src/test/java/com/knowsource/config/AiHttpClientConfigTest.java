package com.knowsource.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

class AiHttpClientConfigTest {

    @Test
    void restClientCustomizerInstallsTimeoutRequestFactory() {
        RestClientCustomizer customizer = new AiHttpClientConfig().aiRestClientCustomizer(123, 456);
        RestClient.Builder builder = RestClient.builder();

        customizer.customize(builder);

        Object requestFactory = ReflectionTestUtils.getField(builder, "requestFactory");
        assertThat(requestFactory).isNotNull();
    }

    @Test
    void webClientCustomizerInstallsReactorConnector() {
        WebClientCustomizer customizer = new AiHttpClientConfig().aiWebClientCustomizer(123, 456);
        WebClient.Builder builder = WebClient.builder();

        customizer.customize(builder);

        WebClient webClient = builder.build();
        assertThat(webClient).isNotNull();
    }
}
