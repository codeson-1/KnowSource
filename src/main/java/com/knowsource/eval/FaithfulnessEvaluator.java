package com.knowsource.eval;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowsource.chat.AnswerGenerator;
import com.knowsource.chat.SourceCitation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 基于 LLM-as-Judge 的 Faithfulness（忠实度）自动评测服务。
 * <p>
 * 对 RAG 生成的答案进行忠实度验证：将答案拆解为原子陈述，交由 LLM 逐一判断是否能从检索到的上下文中推导出来。
 * Faithfulness = faithful_statements / total_statements（0.0 ~ 1.0），如果 LLM 不可用则返回 null 跳过评测。
 */
@Service
public class FaithfulnessEvaluator {

    private static final Logger log = LoggerFactory.getLogger(FaithfulnessEvaluator.class);

    private static final Pattern SCORE_PATTERN = Pattern.compile("\"faithful\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE_PATTERN = Pattern.compile("\"score\"\\s*:\\s*([\\d.]+)");

    private final ObjectProvider<AnswerGenerator> answerGeneratorProvider;
    private final ObjectMapper objectMapper;

    public FaithfulnessEvaluator(
            ObjectProvider<AnswerGenerator> answerGeneratorProvider,
            ObjectMapper objectMapper) {
        this.answerGeneratorProvider = answerGeneratorProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 评测单条用例的 Faithfulness。
     *
     * @param question  用户问题
     * @param answer    LLM 生成的回答
     * @param sources   检索召回的来源分块（用于构建上下文）
     * @return Faithfulness 分数 0.0~1.0，如果 LLM 不可用则返回 null
     */
    public Double evaluate(String question, String answer, List<SourceCitation> sources) {
        AnswerGenerator judge = answerGeneratorProvider.getIfAvailable();
        if (judge == null) {
            log.info("Faithfulness evaluator skipped: no AnswerGenerator available.");
            return null;
        }

        if (answer == null || answer.isBlank()) {
            return 0.0d;
        }

        String contextText = buildContextText(sources);
        if (contextText.isBlank()) {
            return null;
        }

        String prompt = buildPrompt(question, answer, contextText);
        try {
            String response = judge.generate(prompt, List.of());
            return parseScore(response);
        } catch (Exception e) {
            log.warn("Faithfulness evaluation failed for question '{}': {}", question, e.getMessage());
            return null;
        }
    }

    private String buildContextText(List<SourceCitation> sources) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            SourceCitation source = sources.get(i);
            sb.append("[").append(i + 1).append("] ");
            sb.append(source.snippet());
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    String buildPrompt(String question, String answer, String contextText) {
        return """
                你是一个忠实的评测裁判。请判断以下回答是否忠实于给定的上下文（检索到的文档片段）。

                规则：
                1. 将回答拆解为独立的原子陈述。
                2. 对每条陈述，判断它能否从上下文中推导或直接找到依据。
                3. 如果回答中引用了其他知识来源、编造了数值、或包含了上下文中不存在的事实，则该陈述为不忠实。
                4. faithful = 所有陈述都忠实的比例。如果没有任何陈述，视为不忠实（score=0）。

                上下文：
                %s

                问题：%s

                回答：%s

                请仅输出一个JSON对象（不要包含任何其他文本）：
                {"faithful": true或false, "score": 0.0到1.0之间的数值, "reasoning": "用中文简述判定理由"}
                """.formatted(contextText, question, answer);
    }

    Double parseScore(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return null;
        }

        // Try parsing as JSON first
        try {
            FaithfulnessResult result = objectMapper.readValue(llmResponse.strip(), FaithfulnessResult.class);
            if (result.score != null) {
                return clampScore(result.score);
            }
        } catch (JsonProcessingException ignored) {
            // Fall through to regex extraction
        }

        // Regex fallback: extract score from partial/incomplete JSON
        Matcher valueMatcher = VALUE_PATTERN.matcher(llmResponse);
        if (valueMatcher.find()) {
            try {
                return clampScore(Double.parseDouble(valueMatcher.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }

        // Last resort: check if "faithful": true/false exists
        Matcher faithfulMatcher = SCORE_PATTERN.matcher(llmResponse);
        if (faithfulMatcher.find()) {
            return "true".equalsIgnoreCase(faithfulMatcher.group(1)) ? 1.0d : 0.0d;
        }

        return null;
    }

    private static double clampScore(double score) {
        return Math.max(0.0d, Math.min(1.0d, score));
    }

    private record FaithfulnessResult(Boolean faithful, Double score, String reasoning) {
    }
}
