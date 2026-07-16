package com.knowsource.eval;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.knowsource.chat.AnswerGenerator;
import com.knowsource.index.DocumentEmbeddingGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 评测测试共享配置 —— 提供 mock 的 embedding 和 answer generator。
 */
@TestConfiguration
public class EvalTestConfig {

    @Bean
    DocumentEmbeddingGateway documentEmbeddingGateway() {
        return new DocumentEmbeddingGateway() {
            @Override
            public List<float[]> embed(List<String> texts) {
                return embedDocuments(texts);
            }

            @Override
            public List<float[]> embedDocuments(List<String> texts) {
                return texts.stream().map(EvalTestConfig::embedding).toList();
            }

            @Override
            public List<float[]> embedQuery(String text) {
                return List.of(embedding(text));
            }
        };
    }

    @Bean
    AnswerGenerator answerGenerator() {
        return (question, sources) -> "评测回答：" + question;
    }

    static float[] embedding(String text) {
        Set<String> categories = categories(text);
        float[] vec = new float[1024];
        if (categories.contains("leave")) {
            vec[0] = 1.0f;
        }
        if (categories.contains("security")) {
            vec[1] = 1.0f;
        }
        if (categories.contains("expense")) {
            vec[2] = 1.0f;
        }
        if (categories.contains("remote")) {
            vec[3] = 1.0f;
        }
        if (categories.contains("fire")) {
            vec[4] = 1.0f;
        }
        if (categories.contains("training")) {
            vec[5] = 1.0f;
        }
        if (categories.contains("procurement")) {
            vec[6] = 1.0f;
        }
        if (categories.contains("backup")) {
            vec[7] = 1.0f;
        }
        if (categories.isEmpty()) {
            vec[10] = 1.0f;
        }
        return vec;
    }

    private static Set<String> categories(String text) {
        String n = text.toLowerCase(Locale.ROOT);
        java.util.LinkedHashSet<String> cats = new java.util.LinkedHashSet<>();

        if (containsAny(n, "leave", "annual", "年假", "假期", "休年假", "直属经理", "hr", "复核", "全职",
                "carryover", "unused", "结转", "未使用", "leave-2024", "vp", "审批流程")) {
            cats.add("leave");
        }
        if (containsAny(n, "security", "badge", "office", "visitor", "lost",
                "安全", "工牌", "办公区", "访客", "前台", "丢失", "门禁",
                "incident", "24", "事件", "上报", "p0", "补办", "sec-2024")) {
            cats.add("security");
        }
        if (containsAny(n, "expense", "reimbursement", "receipt", "finance", "lodging",
                "报销", "票据", "财务", "住宿", "额度", "餐费", "交通",
                "limit", "800", "exp", "5000", "120", "300")) {
            cats.add("expense");
        }
        if (containsAny(n, "remote", "work", "week", "vpn",
                "远程", "办公", "每周", "团队负责人", "线下培训")) {
            cats.add("remote");
        }
        if (containsAny(n, "消防", "fire", "疏散", "演练")) {
            cats.add("fire");
        }
        if (containsAny(n, "培训", "training", "学时")) {
            cats.add("training");
        }
        if (containsAny(n, "采购", "procurement", "审批")) {
            cats.add("procurement");
        }
        if (containsAny(n, "备份", "backup", "数据")) {
            cats.add("backup");
        }
        return cats;
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
