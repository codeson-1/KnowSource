package com.knowsource.chat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.knowsource.document.ResourceNotFoundException;
import com.knowsource.user.DemoUserService;
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
    private static final String RAG_PROFILE = "naive";

    private final JdbcClient jdbcClient;
    private final DemoUserService demoUserService;
    private final VectorSearchService vectorSearchService;
    private final ObjectProvider<AnswerGenerator> answerGeneratorProvider;
    private final ObjectProvider<StreamingAnswerGenerator> streamingAnswerGeneratorProvider;
    private final ObjectProvider<AsyncTaskExecutor> taskExecutorProvider;
    private final QaTraceService qaTraceService;

    public ChatService(
            JdbcClient jdbcClient,
            DemoUserService demoUserService,
            VectorSearchService vectorSearchService,
            ObjectProvider<AnswerGenerator> answerGeneratorProvider,
            ObjectProvider<StreamingAnswerGenerator> streamingAnswerGeneratorProvider,
            @Qualifier("applicationTaskExecutor") ObjectProvider<AsyncTaskExecutor> taskExecutorProvider,
            QaTraceService qaTraceService) {
        this.jdbcClient = jdbcClient;
        this.demoUserService = demoUserService;
        this.vectorSearchService = vectorSearchService;
        this.answerGeneratorProvider = answerGeneratorProvider;
        this.streamingAnswerGeneratorProvider = streamingAnswerGeneratorProvider;
        this.taskExecutorProvider = taskExecutorProvider;
        this.qaTraceService = qaTraceService;
    }

    public ChatResponse answer(String kbId, ChatRequest request) {
        ChatContext context = prepareContext(kbId, request);
        if (context.refused()) {
            qaTraceService.recordAsync(traceRecord(context, context.fallbackAnswer(), 0, null));
            return new ChatResponse(
                    context.traceId(), context.kbId(), context.question(), context.fallbackAnswer(), true,
                    context.sources());
        }

        AnswerGenerator answerGenerator = answerGeneratorProvider.getIfAvailable();
        long llmStartedAt = System.nanoTime();
        String answer = answerGenerator == null
                ? context.fallbackAnswer()
                : answerGenerator.generate(context.question(), context.sources());
        int llmMs = answerGenerator == null ? 0 : elapsedMillis(llmStartedAt);
        qaTraceService.recordAsync(traceRecord(context, answer, llmMs, null));
        return new ChatResponse(context.traceId(), context.kbId(), context.question(), answer, false, context.sources());
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
        long userId = demoUserService.currentUserId();
        requireKbMember(kbId, userId);
        String question = normalizeQuestion(request.question());

        long retrievalStartedAt = System.nanoTime();
        List<RetrievedChunk> chunks = vectorSearchService.search(kbId, question, request.topK());
        int retrievalMs = elapsedMillis(retrievalStartedAt);
        String traceId = UUID.randomUUID().toString();
        if (chunks.isEmpty()) {
            return new ChatContext(
                    traceId, userId, startedAt, kbId, question, true, List.of(), EMPTY_CONTEXT_ANSWER, retrievalMs);
        }

        List<SourceCitation> sources = toSources(chunks);
        return new ChatContext(
                traceId, userId, startedAt, kbId, question, false, sources, draftAnswer(sources), retrievalMs);
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
                    streamingAnswerGenerator.stream(
                            context.question(),
                            context.sources(),
                            token -> sendToken(emitter, answer, token, firstTokenAt));
                }
            }

            int llmMs = context.refused() || streamingAnswerGenerator == null ? 0 : elapsedMillis(generationStartedAt);
            Integer firstTokenMs = firstTokenAt[0] == 0 ? null : elapsedMillis(context.startedAtNanos(), firstTokenAt[0]);
            qaTraceService.recordAsync(traceRecord(context, answer.toString(), llmMs, firstTokenMs));
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(new ChatStreamDone(
                                    context.traceId(), context.kbId(), context.question(), context.refused(),
                                    answer.toString()),
                            MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (RuntimeException | java.io.IOException ex) {
            qaTraceService.recordAsync(traceRecord(context, answer.toString(), null, null));
            emitter.completeWithError(ex);
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
                context.kbId(),
                context.question(),
                null,
                context.sources(),
                answer,
                context.retrievalMs(),
                llmMs,
                null,
                generationFirstTokenMs,
                elapsedMillis(context.startedAtNanos()),
                RAG_PROFILE);
    }

    private static int elapsedMillis(long startedAtNanos) {
        return elapsedMillis(startedAtNanos, System.nanoTime());
    }

    private static int elapsedMillis(long startedAtNanos, long finishedAtNanos) {
        return (int) Math.max(0L, (finishedAtNanos - startedAtNanos) / 1_000_000L);
    }
}
