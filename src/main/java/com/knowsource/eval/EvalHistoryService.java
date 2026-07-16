package com.knowsource.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.knowsource.security.CurrentUserService;
import org.springframework.stereotype.Service;

/**
 * 评测历史服务 —— 负责评测报告历史的查询与解析。
 */
@Service
public class EvalHistoryService {

    private final CurrentUserService currentUserService;

    public EvalHistoryService(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    /**
     * 列出所有评测历史记录，按生成时间倒序。
     */
    public List<EvalHistoryItem> listHistory() {
        EvalConstants.requireAdmin(currentUserService);
        if (!Files.exists(EvalConstants.REPORTS_DIR_PATH)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(EvalConstants.REPORTS_DIR_PATH)) {
            return files
                    .filter(path -> path.getFileName().toString().startsWith("report-")
                            && path.getFileName().toString().endsWith(".md"))
                    .map(this::parseHistoryItem)
                    .filter(item -> item != null)
                    .sorted(Comparator.comparing(EvalHistoryItem::generatedAt).reversed())
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    private EvalHistoryItem parseHistoryItem(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, String> metrics = parseMetricsTable(content);
            if (metrics.isEmpty()) {
                return null;
            }

            String generatedAtStr = metrics.get("生成时间");
            LocalDateTime generatedAt = generatedAtStr != null ? LocalDateTime.parse(generatedAtStr) : null;
            if (generatedAt == null) {
                return null;
            }

            int totalCases = parseMetricInt(metrics, "用例总数");
            int inScopeCases = parseMetricInt(metrics, "范围内用例");
            int outOfScopeCases = parseMetricInt(metrics, "范围外用例");

            return new EvalHistoryItem(
                    generatedAt,
                    EvalConstants.REPORTS_DIR_PATH.relativize(file).toString(),
                    totalCases,
                    inScopeCases,
                    outOfScopeCases,
                    parseMetricDouble(metrics, "文档命中率@5"),
                    parseMetricDouble(metrics, "引用准确率"),
                    parseMetricDouble(metrics, "拒答准确率"),
                    parseMetricNullable(metrics, "忠实度 (Faithfulness)"));
        } catch (Exception ex) {
            return null;
        }
    }

    static Map<String, String> parseMetricsTable(String content) {
        Map<String, String> metrics = new LinkedHashMap<>();
        boolean inTable = false;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("| 指标 ") || trimmed.startsWith("| Metric ")) {
                inTable = true;
                continue;
            }
            if (!inTable) {
                Matcher genMatch = Pattern.compile("^生成时间:\\s*(.+)$").matcher(trimmed);
                if (genMatch.find()) {
                    metrics.put("生成时间", genMatch.group(1).trim());
                }
                continue;
            }
            if (!trimmed.startsWith("|") || trimmed.contains("---")) {
                break;
            }
            String[] cells = trimmed.split("\\|");
            if (cells.length >= 3) {
                metrics.put(cells[1].trim(), cells[2].trim());
            }
        }
        return metrics;
    }

    private static int parseMetricInt(Map<String, String> metrics, String key) {
        try {
            return Integer.parseInt(metrics.getOrDefault(key, "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** 解析百分比值（如 "83.3%"）返回 0~1 之间的 double。 */
    private static double parseMetricDouble(Map<String, String> metrics, String key) {
        return parseMetricDouble(metrics, key, 0.0d);
    }

    /** 解析可空百分比值，缺失时为 null（如忠实度未跑出来时为 "-"）。 */
    private static Double parseMetricNullable(Map<String, String> metrics, String key) {
        String value = metrics.get(key);
        if (value == null || "-".equals(value.trim())) {
            return null;
        }
        return parseDoubleFromPct(value.trim());
    }

    private static double parseMetricDouble(Map<String, String> metrics, String key, double defaultVal) {
        String value = metrics.get(key);
        if (value == null) {
            return defaultVal;
        }
        return parseDoubleFromPct(value.trim());
    }

    private static double parseDoubleFromPct(String value) {
        try {
            return Double.parseDouble(value.replace("%", "").trim()) / 100.0d;
        } catch (NumberFormatException ignored) {
            return 0.0d;
        }
    }
}
