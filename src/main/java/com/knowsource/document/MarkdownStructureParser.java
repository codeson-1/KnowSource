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

            if (StructuredTableParser.isMarkdownTableStart(lines, index)) {
                flushParagraph(blocks, paragraph, headings);
                List<String> tableLines = new ArrayList<>();
                tableLines.add(lines.get(index));
                tableLines.add(lines.get(index + 1));
                index += 2;
                while (index < lines.size() && StructuredTableParser.looksLikePipeRow(lines.get(index))) {
                    tableLines.add(lines.get(index));
                    index++;
                }
                List<String> sectionPath = activeHeadings(headings);
                ExtractedTable table = StructuredTableParser.parseMarkdownTable(tableLines);
                String tableContent = table == null ? String.join("\n", tableLines) : table.markdown();
                blocks.add(new ExtractedBlock(
                        withHeadingContext(tableContent, sectionPath),
                        null,
                        "TABLE",
                        blocks.size(),
                        sectionPath,
                        sectionPath.isEmpty() ? null : sectionPath.getLast(),
                        table));
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
                    null,
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

    private record Heading(int level, String text) {
    }
}
