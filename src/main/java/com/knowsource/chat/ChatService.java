package com.knowsource.chat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.knowsource.ai.AiProviderException;
import com.knowsource.document.ResourceNotFoundException;
import com.knowsource.security.CurrentUserService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatService {

    private static final int MAX_SNIPPET_LENGTH = 240;
    private static final String EMPTY_CONTEXT_ANSWER =
            "\u77e5\u8bc6\u5e93\u4e2d\u672a\u627e\u5230\u76f8\u5173\u4fe1\u606f\u3002";
    private static final String AI_BUSY_ANSWER =
            "\u5df2\u68c0\u7d22\u5230\u76f8\u5173\u77e5\u8bc6\uff0c\u4f46 AI \u670d\u52a1\u6682\u65f6\u7e41\u5fd9\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";

    private final JdbcClient jdbcClient;
    private final CurrentUserService currentUserService;
    private final RagProfileRouter ragProfileRouter;
    private final VectorSearchService vectorSearchService;
    private final ObjectProvider<AnswerGenerator> answerGeneratorProvider;
    private final ObjectProvider<StreamingAnswerGenerator> streamingAnswerGeneratorProvider;
    private final ObjectProvider<AsyncTaskExecutor> taskExecutorProvider;
    private final QaTraceService qaTraceService;
    private final ChatSessionService chatSessionService;
    private final QueryRewriteService queryRewriteService;

    public ChatService(
            JdbcClient jdbcClient,
            CurrentUserService currentUserService,
            RagProfileRouter ragProfileRouter,
            VectorSearchService vectorSearchService,
            ObjectProvider<AnswerGenerator> answerGeneratorProvider,
            ObjectProvider<StreamingAnswerGenerator> streamingAnswerGeneratorProvider,
            @Qualifier("chatExecutor") ObjectProvider<AsyncTaskExecutor> taskExecutorProvider,
            QaTraceService qaTraceService,
            ChatSessionService chatSessionService,
            QueryRewriteService queryRewriteService) {
        this.jdbcClient = jdbcClient;
        this.currentUserService = currentUserService;
        this.ragProfileRouter = ragProfileRouter;
        this.vectorSearchService = vectorSearchService;
        this.answerGeneratorProvider = answerGeneratorProvider;
        this.streamingAnswerGeneratorProvider = streamingAnswerGeneratorProvider;
        this.taskExecutorProvider = taskExecutorProvider;
        this.qaTraceService = qaTraceService;
        this.chatSessionService = chatSessionService;
        this.queryRewriteService = queryRewriteService;
    }

    public ChatResponse answer(String kbId, ChatRequest request) {
        ChatContext context = prepareContext(kbId, request);
        if (context.refused()) {
            qaTraceService.recordAsync(traceRecord(context, context.fallbackAnswer(), 0, null));
            chatSessionService.appendAssistantMessage(context.sessionId(), context.fallbackAnswer());
            return new ChatResponse(
                    context.traceId(), context.sessionId(), context.kbId(), context.question(), context.rewrittenQuery(),
                    context.ragProfile().value(),
                    context.fallbackAnswer(), true,
                    context.sources());
        }

        AnswerGenerator answerGenerator = answerGeneratorProvider.getIfAvailable();
        long llmStartedAt = System.nanoTime();
        String answer = answerGenerator == null
                ? context.fallbackAnswer()
                : generateAnswer(answerGenerator, context);
        int llmMs = answerGenerator == null ? 0 : elapsedMillis(llmStartedAt);
        qaTraceService.recordAsync(traceRecord(context, answer, llmMs, null));
        chatSessionService.appendAssistantMessage(context.sessionId(), answer);
        return new ChatResponse(
                context.traceId(), context.sessionId(), context.kbId(), context.question(), context.rewrittenQuery(),
                context.ragProfile().value(),
                answer, false, context.sources());
    }

    public SseEmitter stream(String kbId, ChatRequest request) {
        ChatContext context = prepareContext(kbId, request);
        SseEmitter emitter = new SseEmitter(60_000L);
        AsyncTaskExecutor taskExecutor = taskExecutorProvider.getIfAvailable();
        Runnable task = () -> streamContext(context, emitter);

        if (taskExecutor == null) {
            task.run();
        } else {
            taskExecutor.execute(task);
        }

        return emitter;
    }

    private ChatContext prepareContext(String kbId, ChatRequest request) {
        long startedAt = System.nanoTime();
        long userId = currentUserService.currentUserId();
        requireKbMember(kbId, userId);
        String question = normalizeQuestion(request.question());
        ChatSessionHistory sessionHistory = chatSessionService.loadOrCreate(request.sessionId(), userId, kbId, question);
        RagProfile ragProfile = ragProfileRouter.route(request, sessionHistory.hasMessages());
        QueryRewriteResult rewriteResult = queryRewriteService.rewrite(question, sessionHistory.messages(), ragProfile);
        chatSessionService.appendUserMessage(sessionHistory.sessionId(), question);

        long retrievalStartedAt = System.nanoTime();
        List<RetrievedChunk> chunks = vectorSearchService.search(kbId, rewriteResult.retrievalQueries(), request.topK());
        int retrievalMs = elapsedMillis(retrievalStartedAt);
        String traceId = UUID.randomUUID().toString();
        if (chunks.isEmpty()) {
            return new ChatContext(
                    traceId, sessionHistory.sessionId(), userId, startedAt, kbId, question, rewriteResult.query(),
                    rewriteResult.rewrittenQuery(), rewriteResult.rewriteMs(), ragProfile, true, List.of(),
                    EMPTY_CONTEXT_ANSWER,
                    retrievalMs);
        }

        List<SourceCitation> sources = toSources(chunks);
        return new ChatContext(
                traceId, sessionHistory.sessionId(), userId, startedAt, kbId, question, rewriteResult.query(),
                rewriteResult.rewrittenQuery(), rewriteResult.rewriteMs(), ragProfile, false, sources,
                draftAnswer(sources),
                retrievalMs);
    }

    private void streamContext(ChatContext context, SseEmitter emitter) {
        StringBuilder answer = new StringBuilder();
        long generationStartedAt = System.nanoTime();
        long[] firstTokenAt = new long[1];
        StreamingAnswerGenerator streamingAnswerGenerator = null;

        try {
            emitter.send(SseEmitter.event()
                    .name("sources")
                    .data(context.sources(), MediaType.APPLICATION_JSON));

            if (context.refused()) {
                sendToken(emitter, answer, context.fallbackAnswer(), firstTokenAt);
            } else {
                streamingAnswerGenerator = streamingAnswerGeneratorProvider.getIfAvailable();
                if (streamingAnswerGenerator == null) {
                    sendToken(emitter, answer, context.fallbackAnswer(), firstTokenAt);
                } else {
                    try {
                        streamingAnswerGenerator.stream(
                                context.question(),
                                context.sources(),
                                token -> sendToken(emitter, answer, token, firstTokenAt));
                    } catch (AiProviderException ex) {
                        sendToken(emitter, answer, AI_BUSY_ANSWER, firstTokenAt);
                    }
                }
            }

            int llmMs = context.refused() || streamingAnswerGenerator == null ? 0 : elapsedMillis(generationStartedAt);
            Integer firstTokenMs = firstTokenAt[0] == 0 ? null : elapsedMillis(context.startedAtNanos(), firstTokenAt[0]);
            qaTraceService.recordAsync(traceRecord(context, answer.toString(), llmMs, firstTokenMs));
            chatSessionService.appendAssistantMessage(context.sessionId(), answer.toString());
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(new ChatStreamDone(
                                    context.traceId(), context.sessionId(), context.kbId(), context.question(),
                                    context.rewrittenQuery(), context.ragProfile().value(),
                                    context.refused(), answer.toString()),
                            MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (RuntimeException | java.io.IOException ex) {
            qaTraceService.recordAsync(traceRecord(context, answer.toString(), null, null));
            emitter.completeWithError(ex);
        }
    }

    private static String generateAnswer(AnswerGenerator answerGenerator, ChatContext context) {
        try {
            return answerGenerator.generate(context.question(), context.sources());
        } catch (AiProviderException ex) {
            return AI_BUSY_ANSWER;
        }
    }

    private static void sendToken(SseEmitter emitter, StringBuilder answer, String token, long[] firstTokenAt) {
        try {
            if (firstTokenAt[0] == 0) {
                firstTokenAt[0] = System.nanoTime();
            }
            answer.append(token);
            emitter.send(SseEmitter.event().name("token").data(token, MediaType.TEXT_PLAIN));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Failed to send SSE token.", ex);
        }
    }

    private void requireKbMember(String kbId, long userId) {
        Long membershipCount = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM kb_members
                WHERE kb_id = :kbId AND user_id = :userId
                """)
                .param("kbId", kbId)
                .param("userId", userId)
                .query(Long.class)
                .single();

        if (membershipCount == 0) {
            throw new ResourceNotFoundException("Knowledge base not found.");
        }
    }

    private static String normalizeQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("Question is required.");
        }
        return question.trim();
    }

    private static List<SourceCitation> toSources(List<RetrievedChunk> chunks) {
        return IntStream.range(0, chunks.size())
                .mapToObj(index -> toSource(index + 1, chunks.get(index)))
                .toList();
    }

    private static SourceCitation toSource(int index, RetrievedChunk chunk) {
        return new SourceCitation(
                index,
                chunk.chunkId(),
                chunk.docId(),
                chunk.docVersion(),
                chunk.title(),
                chunk.chunkIndex(),
                chunk.pageNumber(),
                snippet(chunk.content()),
                chunk.score());
    }

    private static String snippet(String content) {
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_SNIPPET_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_SNIPPET_LENGTH) + "...";
    }

    private static String draftAnswer(List<SourceCitation> sources) {
        String citations = sources.stream()
                .map(source -> "[" + source.index() + "]")
                .collect(Collectors.joining(" "));
        return "\u5df2\u68c0\u7d22\u5230\u76f8\u5173\u77e5\u8bc6\u7247\u6bb5\uff0c"
                + "\u4e0b\u4e00\u9636\u6bb5\u5c06\u63a5\u5165\u5927\u6a21\u578b\u751f\u6210\u6700\u7ec8\u56de\u7b54\u3002"
                + "\u53c2\u8003\u6765\u6e90\uff1a" + citations;
    }

    private static QaTraceRecord traceRecord(
            ChatContext context, String answer, Integer llmMs, Integer generationFirstTokenMs) {
        return new QaTraceRecord(
                context.traceId(),
                context.userId(),
                context.sessionId(),
                context.kbId(),
                context.question(),
                context.rewrittenQuery(),
                context.sources(),
                answer,
                context.retrievalMs(),
                llmMs,
                context.rewriteMs(),
                generationFirstTokenMs,
                elapsedMillis(context.startedAtNanos()),
                context.ragProfile().value());
    }

    private static int elapsedMillis(long startedAtNanos) {
        return elapsedMillis(startedAtNanos, System.nanoTime());
    }

    private static int elapsedMillis(long startedAtNanos, long finishedAtNanos) {
        return (int) Math.max(0L, (finishedAtNanos - startedAtNanos) / 1_000_000L);
    }
}
