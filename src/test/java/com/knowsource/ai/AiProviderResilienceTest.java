package com.knowsource.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class AiProviderResilienceTest {

    @Test
    void embeddingCallsAreRetried() {
        AiProviderResilience resilience = new AiProviderResilience(
                10, 1, 0, 10, 0,
                10, 1, 0, 10, 0,
                3, 1,
                10, 1, 0, 10, 0);
        AtomicInteger attempts = new AtomicInteger();

        String result = resilience.executeEmbedding(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("temporary embedding failure");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void chatCallsAreNotRetried() {
        AiProviderResilience resilience = new AiProviderResilience(
                10, 1, 0, 10, 0,
                10, 1, 0, 10, 0,
                3, 1,
                10, 1, 0, 10, 0);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> resilience.executeChat(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("chat failure");
                }))
                .isInstanceOf(AiProviderException.class)
                .hasMessage("AI chat call failed.");

        assertThat(attempts).hasValue(1);
    }

    @Test
    void rerankCallsAreNotRetried() {
        AiProviderResilience resilience = new AiProviderResilience(
                10, 1, 0, 10, 0,
                10, 1, 0, 10, 0,
                3, 1,
                10, 1, 0, 10, 0);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> resilience.executeRerank(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("rerank failure");
                }))
                .isInstanceOf(AiProviderException.class)
                .hasMessage("AI rerank call failed.");

        assertThat(attempts).hasValue(1);
    }
}
