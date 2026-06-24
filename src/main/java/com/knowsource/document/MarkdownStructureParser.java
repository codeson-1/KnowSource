package com.knowsource.document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class MarkdownStructureParser {

    ExtractedDocument parse(String markdown) {
        List<ExtractedBlock> blocks = new ArrayList<>();
        String[] headings = new String[6];
        List<String> paragraph = new ArrayList<>();
        List<String> lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines().toList();

        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index);
            if (!StringUtils.hasText(line)) {
                flushParagraph(blocks, paragraph, headings);
                index++;
                continue;
            }

            Heading heading = parseHeading(line);
            if (heading != null) {
                flushParagraph(blocks, paragraph, headings);
                headings[heading.level() - 1] = heading.text();
                Arrays.fill(headings, heading.level(), headings.length, null);
                index++;
                continue;
            }

            if (isTableStart(lines, index)) {
                flushParagraph(blocks, paragraph, headings);
                List<String> tableLines = new ArrayList<>();
                tableLines.add(lines.get(index));
                tableLines.add(lines.get(index + 1));
                index += 2;
                while (index < lines.size() && looksLikeTableRow(lines.get(index))) {
                    tableLines.add(lines.get(index));
                    index++;
                }
                List<String> sectionPath = activeHeadings(headings);
                blocks.add(new ExtractedBlock(
                        withHeadingContext(String.join("\n", tableLines), sectionPath),
                        null,
                        "TABLE",
                        blocks.size(),
                        sectionPath,
                        sectionPath.isEmpty() ? null : sectionPath.getLast()));
                continue;
            }

            paragraph.add(line);
            index++;
        }

        flushParagraph(blocks, paragraph, headings);
        return new ExtractedDocument(blocks);
    }

    private void flushParagraph(List<ExtractedBlock> blocks, List<String> paragraph, String[] headings) {
        if (paragraph.isEmpty()) {
            return;
        }
        String content = String.join("\n", paragraph).trim();
        paragraph.clear();
        if (StringUtils.hasText(content)) {
            List<String> sectionPath = activeHeadings(headings);
            blocks.add(new ExtractedBlock(
                    withHeadingContext(content, sectionPath),
                    null,
                    "TEXT",
                    blocks.size(),
                    sectionPath,
                    null));
        }
    }

    private String withHeadingContext(String content, List<String> sectionPath) {
        if (sectionPath.isEmpty()) {
            return content.trim();
        }
        return String.join(" > ", sectionPath) + "\n\n" + content.trim();
    }

    private List<String> activeHeadings(String[] headings) {
        return Arrays.stream(headings)
                .filter(StringUtils::hasText)
                .toList();
    }

    private Heading parseHeading(String line) {
        String trimmed = line.trim();
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') {
            level++;
        }
        if (level < 1 || level > 6 || level >= trimmed.length() || trimmed.charAt(level) != ' ') {
            return null;
        }
        String text = trimmed.substring(level + 1).trim();
        return StringUtils.hasText(text) ? new Heading(level, text) : null;
    }

    private boolean isTableStart(List<String> lines, int index) {
        return index + 1 < lines.size()
                && looksLikeTableRow(lines.get(index))
                && looksLikeTableSeparator(lines.get(index + 1));
    }

    private boolean looksLikeTableRow(String line) {
        String trimmed = line.trim();
        return trimmed.indexOf('|') >= 0 && trimmed.chars().filter(ch -> ch == '|').count() >= 2;
    }

    private boolean looksLikeTableSeparator(String line) {
        String normalized = line.trim();
        if (!looksLikeTableRow(normalized)) {
            return false;
        }
        return normalized.replace("|", "")
                .replace(":", "")
                .replace("-", "")
                .replace(" ", "")
                .isEmpty();
    }

    private record Heading(int level, String text) {
    }
}
