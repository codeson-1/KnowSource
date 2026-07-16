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

import com.knowsource.security.CurrentUser;
import com.knowsource.security.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * 评测历史服务 —— 负责评测报告历史的查询与解析。
 * <p>
 * 从 EvalRunnerService 拆分而来，遵循单一职责原则。
 */
@Service
public class EvalHistoryService {

    private static final Path REPORTS_DIR = Path.of("docs/eval/reports");

    private final CurrentUserService currentUserService;

    public EvalHistoryService(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    /**
     * 列出所有评测历史记录，按生成时间倒序。
     */
    public List<EvalHistoryItem> listHistory() {
        requireAdmin();
        if (!Files.exists(REPORTS_DIR)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(REPORTS_DIR)) {
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
                    REPORTS_DIR.relativize(file).toString(),
                    totalCases,
                    inScopeCases,
                    outOfScopeCases,
                    parseMetricPct(metrics, "文档命中率@5"),
                    parseMetricPct(metrics, "引用准确率"),
                    parseMetricPct(metrics, "拒答准确率"),
                    parseMetricDouble(metrics, "忠实度 (Faithfulness)"));
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
                // 提取 "生成时间: xxx" 行
                Matcher genMatch = Pattern.compile("^生成时间:\\s*(.+)$").matcher(trimmed);
                if (genMatch.find()) {
                    metrics.put("生成时间", genMatch.group(1).trim());
                }
                continue;
            }
            if (!trimmed.startsWith("|")) {
                break;
            }
            if (trimmed.contains("---")) {
                continue;
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

    private static double parseMetricPct(Map<String, String> metrics, String key) {
        String value = metrics.get(key);
        if (value == null) {
            return 0.0d;
        }
        try {
            String num = value.replace("%", "").trim();
            return Double.parseDouble(num) / 100.0d;
        } catch (NumberFormatException ignored) {
            return 0.0d;
        }
    }

    private static Double parseMetricDouble(Map<String, String> metrics, String key) {
        String value = metrics.get(key);
        if (value == null || "-".equals(value.trim())) {
            return null;
        }
        try {
            String num = value.replace("%", "").trim();
            return Double.parseDouble(num) / 100.0d;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void requireAdmin() {
        CurrentUser currentUser = currentUserService.currentUser();
        if (!"ADMIN".equals(currentUser.globalRole())) {
            throw new AccessDeniedException("ADMIN access is required.");
        }
    }
}
