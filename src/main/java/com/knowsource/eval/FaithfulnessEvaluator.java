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
    /** 匹配 ```json ... ``` 或 ``` ... ``` 包裹的代码块 */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");

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
            Double score = parseScore(response);
            if (score != null) {
                log.debug("Faithfulness score for '{}': {}", question, score);
            } else {
                log.warn("Faithfulness score is null for question '{}', raw response: {}", question,
                        response == null ? "<null>" : (response.length() > 200 ? response.substring(0, 200) + "..." : response));
            }
            return score;
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

                请仅输出一个JSON对象（不要包含markdown代码块标记、不要包含任何其他文本）。
                示例格式如下，请替换为实际判定值：
                {"faithful": true, "score": 0.8, "reasoning": "回答中的2条陈述均可从上下文推导，1条无法验证"}
                """.formatted(contextText, question, answer);
    }

    /**
     * 解析 LLM 返回的 Faithfulness 评分。
     * <p>
     * 解析顺序：
     * <ol>
     *   <li>清理 markdown 代码块标记后尝试 JSON 解析</li>
     *   <li>JSON score 字段 → clamp 后返回</li>
     *   <li>JSON 有 faithful 但无 score → true=1.0, false=0.0</li>
     *   <li>正则提取 "score": 数字</li>
     *   <li>正则提取 "faithful": true/false</li>
     *   <li>全部失败 → null</li>
     * </ol>
     */
    Double parseScore(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return null;
        }

        String cleaned = stripCodeBlock(llmResponse).strip();

        // 1. Try parsing as JSON first
        try {
            FaithfulnessResult result = objectMapper.readValue(cleaned, FaithfulnessResult.class);
            if (result.score != null) {
                return clampScore(result.score);
            }
            // JSON 解析成功但 score 为 null，用 faithful 字段兜底
            if (result.faithful != null) {
                return result.faithful ? 1.0d : 0.0d;
            }
        } catch (JsonProcessingException ignored) {
            // Fall through to regex extraction
        }

        // 2. Regex fallback: extract score from partial/incomplete JSON
        Matcher valueMatcher = VALUE_PATTERN.matcher(llmResponse);
        if (valueMatcher.find()) {
            try {
                return clampScore(Double.parseDouble(valueMatcher.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }

        // 3. Last resort: check if "faithful": true/false exists
        Matcher faithfulMatcher = SCORE_PATTERN.matcher(llmResponse);
        if (faithfulMatcher.find()) {
            return "true".equalsIgnoreCase(faithfulMatcher.group(1)) ? 1.0d : 0.0d;
        }

        return null;
    }

    /**
     * 去除 LLM 响应外层包裹的 markdown 代码块标记（```json ... ``` 或 ``` ... ```）。
     * 如果没有代码块包裹，原样返回。
     */
    private static String stripCodeBlock(String response) {
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return response;
    }

    private static double clampScore(double score) {
        return Math.max(0.0d, Math.min(1.0d, score));
    }

    private record FaithfulnessResult(Boolean faithful, Double score, String reasoning) {
    }
}
