package com.knowsource.spike;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/spike")
@ConditionalOnProperty(prefix = "knowsource.spike", name = "enabled", havingValue = "true")
public class SpikeController {

    private static final String KB_ID = "kb-spike";
    private static final String DOC_ID = "doc-spike-handbook";
    private static final int DOC_VERSION = 1;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public SpikeController(VectorStore vectorStore, ChatClient.Builder chatClientBuilder) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
    }

    @PostMapping("/vectors/seed")
    public Map<String, Object> seedVectors() {
        List<Document> documents = List.of(
                new Document("KnowSource 是一个企业级 RAG 智能文档问答系统，核心链路包括上传、解析、切块、发布索引、检索和引用溯源。",
                        metadata("chunk-1")),
                new Document("MVP 版本采用 PostgreSQL 和 pgvector 存储向量，发布期间文档不可检索，索引状态为 SYNCED 后恢复可检索。",
                        metadata("chunk-2")),
                new Document("检索过滤必须使用知识库 ID、发布状态以及 docId 和 docVersion 的组合，避免跨文档版本号误召回。",
                        metadata("chunk-3")));

        vectorStore.add(documents);
        return Map.of("inserted", documents.size(), "kbId", KB_ID, "docId", DOC_ID, "docVersion", DOC_VERSION);
    }

    @GetMapping("/vectors/search")
    public List<SpikeSearchResult> search(@RequestParam(defaultValue = "KnowSource 如何保证检索安全") String query) {
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(5)
                .build());

        return results.stream()
                .map(document -> new SpikeSearchResult(document.getText(), document.getMetadata()))
                .toList();
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestParam(defaultValue = "用一句话介绍 KnowSource") String question) {
        SseEmitter emitter = new SseEmitter(60_000L);
        chatClient.prompt()
                .user(question)
                .stream()
                .content()
                .subscribe(token -> send(emitter, token), emitter::completeWithError, emitter::complete);
        return emitter;
    }

    private Map<String, Object> metadata(String chunkId) {
        return Map.of(
                "kbId", KB_ID,
                "docId", DOC_ID,
                "docVersion", DOC_VERSION,
                "status", "published",
                "chunkId", chunkId);
    }

    private void send(SseEmitter emitter, String token) {
        try {
            emitter.send(SseEmitter.event().name("token").data(token));
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        }
    }

    public record SpikeSearchResult(String text, Map<String, Object> metadata) {
    }
}
