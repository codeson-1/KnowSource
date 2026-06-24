package com.knowsource.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class QueryRewriteServiceTest {

    private final QueryRewriteService queryRewriteService =
            new QueryRewriteService(emptyChatClientProvider(), null, false, true, 2);

    @Test
    void naiveProfileKeepsSingleOriginalQuery() {
        QueryRewriteResult result = queryRewriteService.rewrite(
                "How many annual leave days are available?",
                List.of(userMessage("Tell me about annual leave.")),
                RagProfile.NAIVE);

        assertThat(result.query()).isEqualTo("How many annual leave days are available?");
        assertThat(result.rewrittenQuery()).isNull();
        assertThat(result.retrievalQueries()).containsExactly("How many annual leave days are available?");
    }

    @Test
    void modularFollowUpFallsBackToStandaloneRewriteAndAddsOriginalAsSecondQuery() {
        QueryRewriteResult result = queryRewriteService.rewrite(
                "What is its approval process?",
                List.of(userMessage("How many annual leave days are available?")),
                RagProfile.MODULAR);

        assertThat(result.query())
                .isEqualTo("How many annual leave days are available? What is its approval process?");
        assertThat(result.rewrittenQuery()).isEqualTo(result.query());
        assertThat(result.retrievalQueries()).containsExactly(
                "How many annual leave days are available? What is its approval process?",
                "What is its approval process?");
    }

    @Test
    void modularNonFollowUpAddsPreviousQuestionAsSecondQueryForRecallExpansion() {
        QueryRewriteResult result = queryRewriteService.rewrite(
                "When should reimbursement receipts be submitted?",
                List.of(userMessage("What is required in the office for security?")),
                RagProfile.MODULAR);

        assertThat(result.rewrittenQuery()).isNull();
        assertThat(result.retrievalQueries()).containsExactly(
                "When should reimbursement receipts be submitted?",
                "What is required in the office for security? When should reimbursement receipts be submitted?");
    }

    private static ChatMessage userMessage(String content) {
        return new ChatMessage("USER", content);
    }

    private static ObjectProvider<ChatClient.Builder> emptyChatClientProvider() {
        return new DefaultListableBeanFactory().getBeanProvider(ChatClient.Builder.class);
    }
}
