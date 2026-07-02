package com.knowsource.document;

import java.util.ArrayList;
import java.util.List;

import org.springframework.util.StringUtils;

final class StructuredTableParser {

    private StructuredTableParser() {
    }

    static boolean isMarkdownTableStart(List<String> lines, int index) {
        return index + 1 < lines.size()
                && looksLikePipeRow(lines.get(index))
                && looksLikeMarkdownSeparator(lines.get(index + 1));
    }

    static boolean looksLikePipeRow(String line) {
        String trimmed = line == null ? "" : line.trim();
        return trimmed.indexOf('|') >= 0 && trimmed.chars().filter(ch -> ch == '|').count() >= 2;
    }

    static ExtractedTable parseMarkdownTable(List<String> tableLines) {
        if (tableLines == null || tableLines.size() < 2) {
            return null;
        }
        List<String> headers = splitPipeRow(tableLines.getFirst());
        List<List<String>> rows = new ArrayList<>();
        for (int i = 2; i < tableLines.size(); i++) {
            rows.add(normalizeColumns(splitPipeRow(tableLines.get(i)), headers.size()));
        }
        return new ExtractedTable(headers, rows, toMarkdown(headers, rows));
    }

    static ExtractedTable parseIfTable(String block) {
        if (!StringUtils.hasText(block)) {
            return null;
        }
        List<String> lines = block.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        if (lines.size() < 2) {
            return null;
        }
        ExtractedTable pipeTable = parsePipeTable(lines);
        if (pipeTable != null) {
            return pipeTable;
        }
        return parseWhitespaceTable(lines);
    }

    private static ExtractedTable parsePipeTable(List<String> lines) {
        long pipeRows = lines.stream().filter(StructuredTableParser::looksLikePipeRow).count();
        if (pipeRows < 2) {
            return null;
        }
        List<String> headers = splitPipeRow(lines.getFirst());
        int start = looksLikeMarkdownSeparator(lines.get(1)) ? 2 : 1;
        List<List<String>> rows = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            if (looksLikeMarkdownSeparator(lines.get(i))) {
                continue;
            }
            rows.add(normalizeColumns(splitPipeRow(lines.get(i)), headers.size()));
        }
        if (headers.size() < 2 || rows.isEmpty()) {
            return null;
        }
        return new ExtractedTable(headers, rows, toMarkdown(headers, rows));
    }

    private static ExtractedTable parseWhitespaceTable(List<String> lines) {
        List<List<String>> parsedRows = lines.stream()
                .map(StructuredTableParser::splitWhitespaceRow)
                .filter(row -> row.size() >= 2)
                .toList();
        if (parsedRows.size() < 2) {
            return null;
        }
        int columnCount = parsedRows.stream().mapToInt(List::size).max().orElse(0);
        if (columnCount < 2) {
            return null;
        }
        long compatibleRows = parsedRows.stream()
                .filter(row -> row.size() >= Math.max(2, columnCount - 1))
                .count();
        if (compatibleRows < 2) {
            return null;
        }
        List<String> headers = normalizeColumns(parsedRows.getFirst(), columnCount);
        List<List<String>> rows = parsedRows.stream()
                .skip(1)
                .map(row -> normalizeColumns(row, columnCount))
                .toList();
        return new ExtractedTable(headers, rows, toMarkdown(headers, rows));
    }

    private static List<String> splitPipeRow(String line) {
        String normalized = line == null ? "" : line.trim();
        if (normalized.startsWith("|")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("|")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : normalized.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    private static List<String> splitWhitespaceRow(String line) {
        String[] cells = line.trim().split("\\t+|\\s{2,}");
        List<String> values = new ArrayList<>();
        for (String cell : cells) {
            if (StringUtils.hasText(cell)) {
                values.add(cell.trim());
            }
        }
        return values;
    }

    private static List<String> normalizeColumns(List<String> row, int columnCount) {
        List<String> normalized = new ArrayList<>(row == null ? List.of() : row);
        while (normalized.size() < columnCount) {
            normalized.add("");
        }
        if (normalized.size() > columnCount) {
            return new ArrayList<>(normalized.subList(0, columnCount));
        }
        return normalized;
    }

    private static boolean looksLikeMarkdownSeparator(String line) {
        if (!looksLikePipeRow(line)) {
            return false;
        }
        return line.trim()
                .replace("|", "")
                .replace(":", "")
                .replace("-", "")
                .replace(" ", "")
                .isEmpty();
    }

    private static String toMarkdown(List<String> headers, List<List<String>> rows) {
        List<String> safeHeaders = headers.isEmpty()
                ? defaultHeaders(rows.stream().mapToInt(List::size).max().orElse(0))
                : headers;
        StringBuilder builder = new StringBuilder();
        appendMarkdownRow(builder, safeHeaders);
        appendMarkdownRow(builder, safeHeaders.stream().map(ignored -> "---").toList());
        for (List<String> row : rows) {
            appendMarkdownRow(builder, normalizeColumns(row, safeHeaders.size()));
        }
        return builder.toString().trim();
    }

    private static List<String> defaultHeaders(int count) {
        List<String> headers = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            headers.add("Column " + i);
        }
        return headers;
    }

    private static void appendMarkdownRow(StringBuilder builder, List<String> cells) {
        builder.append("| ");
        builder.append(String.join(" | ", cells.stream().map(StructuredTableParser::escapeMarkdownCell).toList()));
        builder.append(" |\n");
    }

    private static String escapeMarkdownCell(String value) {
        return (value == null ? "" : value.trim()).replace("|", "\\|");
    }
}
