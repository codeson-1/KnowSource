package com.knowsource.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 评测报告渲染器 —— 负责将评测结果渲染为 Markdown 报告并写入文件系统。
 */
@Component
public class EvalReportRenderer {

    /**
     * 渲染 Markdown 格式的评测报告并写入文件。
     */
    public void renderAndWrite(LocalDateTime generatedAt, EvalSummaryResponse summary, List<EvalCaseResponse> results) {
        String report = renderMarkdown(generatedAt, summary, results);
        writeToFiles(report, generatedAt);
    }

    private String renderMarkdown(LocalDateTime generatedAt, EvalSummaryResponse summary, List<EvalCaseResponse> results) {
        StringBuilder report = new StringBuilder();
        report.append("# KnowSource 评测报告\n\n");
        report.append("生成时间: ").append(generatedAt).append("\n\n");
        report.append("| 指标 | 数值 |\n");
        report.append("|---|---:|\n");
        report.append("| 用例总数 | ").append(summary.totalCases()).append(" |\n");
        report.append("| 范围内用例 | ").append(summary.inScopeCases()).append(" |\n");
        report.append("| 范围外用例 | ").append(summary.outOfScopeCases()).append(" |\n");
        report.append("| 文档命中率@5 | ").append(formatPercent(summary.documentHitRate())).append(" |\n");
        report.append("| 引用准确率 | ").append(formatPercent(summary.citationHitRate())).append(" |\n");
        report.append("| 拒答准确率 | ").append(formatPercent(summary.refusalAccuracy())).append(" |\n");
        report.append("| 关键词命中率 | ").append(formatPercent(summary.keywordHitRate())).append(" |\n");
        if (summary.faithfulness() != null) {
            report.append("| 忠实度 (Faithfulness) | ").append(formatPercent(summary.faithfulness())).append(" |\n");
        }
        report.append("| MRR | ").append(String.format("%.3f", summary.mrr())).append(" |\n");
        report.append("| 平均文档排名 | ").append(String.format("%.1f", summary.meanDocRank())).append(" |\n\n");
        report.append("## 用例结果\n\n");
        report.append("| 用例 ID | 前置问题 | 问题 | 期望 | 是否拒答 | 来源文档 | 文档命中 | 引用准确 | 是否通过 | 关键词命中 | 命中关键词 | 文档排名 | 忠实度 | Trace |\n");
        report.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (EvalCaseResponse result : results) {
            report.append("| ")
                    .append(result.id()).append(" | ")
                    .append(escape(result.setupQuestion())).append(" | ")
                    .append(escape(result.question())).append(" | ")
                    .append(result.expected()).append(" | ")
                    .append(result.refused() ? "是" : "否").append(" | ")
                    .append(escape(String.join(", ", result.sourceTitles()))).append(" | ")
                    .append(result.documentHit() ? "是" : "否").append(" | ")
                    .append(result.citationHit() ? "是" : "否").append(" | ")
                    .append(result.passed() ? "是" : "否").append(" | ")
                    .append(result.keywordHit() ? "是" : "否").append(" | ")
                    .append(escape(result.matchedKeyword())).append(" | ")
                    .append(result.docRank() == 0 ? "-" : String.valueOf(result.docRank())).append(" | ")
                    .append(result.faithfulness() != null ? formatPercent(result.faithfulness()) : "-").append(" | ")
                    .append(result.qaTraceId())
                    .append(" |\n");
        }
        return report.toString();
    }

    private void writeToFiles(String report, LocalDateTime generatedAt) {
        try {
            Files.createDirectories(EvalConstants.REPORT_PATH.getParent());
            Files.writeString(EvalConstants.REPORT_PATH, report, StandardCharsets.UTF_8);

            String timestamp = generatedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Files.createDirectories(EvalConstants.REPORTS_DIR_PATH);
            Files.writeString(EvalConstants.REPORTS_DIR_PATH.resolve("report-" + timestamp + ".md"), report, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write eval report.", ex);
        }
    }

    public static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    public static String formatPercent(double value) {
        return "%.1f%%".formatted(value * 100.0d);
    }
}
