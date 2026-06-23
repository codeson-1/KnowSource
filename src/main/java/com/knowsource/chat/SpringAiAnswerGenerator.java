package com.knowsource.chat;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(ChatClient.Builder.class)
class SpringAiAnswerGenerator implements AnswerGenerator, StreamingAnswerGenerator {

    private final ChatClient chatClient;

    SpringAiAnswerGenerator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String generate(String question, List<SourceCitation> sources) {
        return chatClient.prompt()
                .system("""
                        你是一个企业知识库问答助手。请严格基于给定上下文回答问题。
                        如果上下文不足以回答，只能回答“知识库中未找到相关信息。”。
                        每个关键事实后标注引用编号，例如 [1]。
                        """)
                .user("""
                        上下文：
                        %s

                        用户问题：%s
                        """.formatted(context(sources), question))
                .call()
                .content();
    }

    @Override
    public void stream(String question, List<SourceCitation> sources, Consumer<String> tokenConsumer) {
        chatClient.prompt()
                .system("""
                        你是一个企业知识库问答助手。请严格基于给定上下文回答问题。
                        如果上下文不足以回答，只能回答“知识库中未找到相关信息。”。
                        每个关键事实后标注引用编号，例如 [1]。
                        """)
                .user("""
                        上下文：
                        %s

                        用户问题：%s
                        """.formatted(context(sources), question))
                .stream()
                .content()
                .doOnNext(tokenConsumer)
                .blockLast();
    }

    private static String context(List<SourceCitation> sources) {
        return sources.stream()
                .map(source -> "[%d] %s".formatted(source.index(), source.snippet()))
                .collect(Collectors.joining("\n"));
    }
}
