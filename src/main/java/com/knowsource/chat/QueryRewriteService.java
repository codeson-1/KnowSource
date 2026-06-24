package com.knowsource.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.knowsource.ai.AiProviderException;
import com.knowsource.ai.AiProviderResilience;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class QueryRewriteService {

    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final AiProviderResilience aiProviderResilience;
    private final boolean compressionQueryEnabled;
    private final boolean multiQueryEnabled;
    private final int multiQueryCount;

    QueryRewriteService(
            ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
            AiProviderResilience aiProviderResilience,
            @Value("${knowsource.modular-rag.compression-query.enabled:true}") boolean compressionQueryEnabled,
            @Value("${knowsource.modular-rag.multi-query.enabled:true}") boolean multiQueryEnabled,
            @Value("${knowsource.modular-rag.multi-query.count:2}") int multiQueryCount) {
        this.chatClientBuilderProvider = chatClientBuilderProvider;
        this.aiProviderResilience = aiProviderResilience;
        this.compressionQueryEnabled = compressionQueryEnabled;
        this.multiQueryEnabled = multiQueryEnabled;
        this.multiQueryCount = Math.max(1, Math.min(2, multiQueryCount));
    }

    QueryRewriteResult rewrite(String question, List<ChatMessage> history, RagProfile ragProfile) {
        long startedAt = System.nanoTime();
        if (ragProfile != RagProfile.MODULAR) {
            return result(question, null, List.of(question), startedAt);
        }

        String rewritten = rewriteStandaloneQuestion(question, history);
        List<String> retrievalQueries = retrievalQueries(question, rewritten, history);
        return result(rewritten, rewritten.equals(question) ? null : rewritten, retrievalQueries, startedAt);
    }

    private String rewriteStandaloneQuestion(String question, List<ChatMessage> history) {
        if (history.isEmpty() || !looksLikeFollowUp(question)) {
            return question;
        }

        String modelRewrite = compressionQuery(question, history);
        if (StringUtils.hasText(modelRewrite)) {
            return modelRewrite;
        }

        String previousUserQuestion = latestUserQuestion(history);
        if (!StringUtils.hasText(previousUserQuestion)) {
            return question;
        }

        return previousUserQuestion + " " + question;
    }

    private String compressionQuery(String question, List<ChatMessage> history) {
        if (!compressionQueryEnabled) {
            return null;
        }
        try {
            ChatClient.Builder chatClientBuilder = chatClientBuilderProvider.getIfAvailable();
            if (chatClientBuilder == null) {
                return null;
            }
            String rewritten = aiProviderResilience.executeChat(() -> chatClientBuilder.build()
                    .prompt()
                    .system("""
                            Rewrite the follow-up question into a standalone retrieval query.
                            Use only the conversation history for references.
                            Return one concise query only, without explanation or quotes.
                            """)
                    .user("""
                            Conversation:
                            %s

                            Follow-up question:
                            %s
                            """.formatted(historyText(history), question))
                    .call()
                    .content());
            return sanitizeOneLine(rewritten);
        } catch (AiProviderException | BeansException ex) {
            return null;
        }
    }

    private List<String> retrievalQueries(String question, String rewritten, List<ChatMessage> history) {
        Set<String> queries = new LinkedHashSet<>();
        addQuery(queries, rewritten);
        if (!multiQueryEnabled || multiQueryCount == 1) {
            return List.copyOf(queries);
        }

        addQuery(queries, alternateQuery(question, rewritten, history));
        return List.copyOf(queries);
    }

    private String alternateQuery(String question, String rewritten, List<ChatMessage> history) {
        if (!question.equals(rewritten)) {
            return question;
        }
        String latestUserQuestion = latestUserQuestion(history);
        if (StringUtils.hasText(latestUserQuestion) && !latestUserQuestion.equals(question)) {
            return latestUserQuestion + " " + question;
        }
        return null;
    }

    private static QueryRewriteResult result(
            String query, String rewrittenQuery, List<String> retrievalQueries, long startedAt) {
        return new QueryRewriteResult(query, rewrittenQuery, retrievalQueries, elapsedMillis(startedAt));
    }

    private static void addQuery(Set<String> queries, String query) {
        if (StringUtils.hasText(query)) {
            queries.add(query.trim());
        }
    }

    private static String sanitizeOneLine(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String sanitized = value.strip()
                .replaceAll("^```[a-zA-Z]*", "")
                .replaceAll("```$", "")
                .replaceAll("^[\"']|[\"']$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return StringUtils.hasText(sanitized) ? sanitized : null;
    }

    private static String historyText(List<ChatMessage> history) {
        List<ChatMessage> recent = new ArrayList<>(history);
        int fromIndex = Math.max(0, recent.size() - 6);
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : recent.subList(fromIndex, recent.size())) {
            builder.append(message.role()).append(": ").append(message.content()).append('\n');
        }
        return builder.toString().trim();
    }

    private static String latestUserQuestion(List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage message = history.get(i);
            if ("USER".equals(message.role())) {
                return message.content();
            }
        }
        return null;
    }

    private static boolean looksLikeFollowUp(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        return normalized.contains(" it ")
                || normalized.startsWith("it ")
                || normalized.contains(" that ")
                || normalized.startsWith("that ")
                || normalized.contains(" its ")
                || normalized.startsWith("its ")
                || normalized.startsWith("this ")
                || normalized.contains("this ")
                || normalized.contains("这个")
                || normalized.contains("它")
                || normalized.contains("该")
                || normalized.contains("上面")
                || normalized.contains("刚才");
    }

    private static int elapsedMillis(long startedAtNanos) {
        return (int) Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }
}
