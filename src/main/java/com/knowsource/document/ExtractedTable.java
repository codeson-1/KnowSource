package com.knowsource.document;

import java.util.List;

public record ExtractedTable(
        List<String> headers,
        List<List<String>> rows,
        String markdown) {

    public ExtractedTable {
        headers = headers == null ? List.of() : List.copyOf(headers);
        rows = rows == null
                ? List.of()
                : rows.stream()
                        .map(row -> row == null ? List.<String>of() : List.copyOf(row))
                        .toList();
        markdown = markdown == null ? "" : markdown;
    }

    public int rowCount() {
        return rows.size();
    }

    public int columnCount() {
        if (!headers.isEmpty()) {
            return headers.size();
        }
        return rows.stream()
                .mapToInt(List::size)
                .max()
                .orElse(0);
    }
}
