package com.knowsource.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorSettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AiHttpClientConfig {

    @Bean
    RestClientCustomizer aiRestClientCustomizer(
            @Value("${knowsource.ai.http.connect-timeout-millis:5000}") long connectTimeoutMillis,
            @Value("${knowsource.ai.http.read-timeout-millis:30000}") long readTimeoutMillis) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(Math.max(1L, connectTimeoutMillis)))
                .withReadTimeout(Duration.ofMillis(Math.max(1L, readTimeoutMillis)));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        return builder -> builder.requestFactory(requestFactory);
    }

    @Bean
    WebClientCustomizer aiWebClientCustomizer(
            @Value("${knowsource.ai.http.connect-timeout-millis:5000}") long connectTimeoutMillis,
            @Value("${knowsource.ai.http.stream-read-timeout-millis:60000}") long streamReadTimeoutMillis) {
        ClientHttpConnectorSettings settings = ClientHttpConnectorSettings.defaults()
                .withConnectTimeout(Duration.ofMillis(Math.max(1L, connectTimeoutMillis)))
                .withReadTimeout(Duration.ofMillis(Math.max(1L, streamReadTimeoutMillis)));
        ClientHttpConnector connector = ClientHttpConnectorBuilder.detect().build(settings);
        return builder -> customizeWebClient(builder, connector);
    }

    private static void customizeWebClient(WebClient.Builder builder, ClientHttpConnector connector) {
        builder.clientConnector(connector);
    }
}
