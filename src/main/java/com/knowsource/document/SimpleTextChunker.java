package com.knowsource.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SimpleTextChunker {

    private static final int PARENT_CHUNK_SIZE = 1_200;
    private static final int CHILD_CHUNK_SIZE = 400;

    public List<ParentChunk> split(String content) {
        return split(ExtractedDocument.text(content));
    }

    public List<ParentChunk> split(ExtractedDocument document) {
        List<ParentChunk> parents = new ArrayList<>();
        int parentIndex = 0;
        int fallbackBlockIndex = 0;

        for (ExtractedBlock block : document.blocks()) {
            String content = block.content();
            if (!StringUtils.hasText(content)) {
                fallbackBlockIndex++;
                continue;
            }
            int blockIndex = block.blockIndex() == null ? fallbackBlockIndex : block.blockIndex();
            for (TextRange parentRange : splitRanges(content, PARENT_CHUNK_SIZE, "TABLE".equals(normalizeChunkType(block.chunkType())))) {
                String parentContent = content.substring(parentRange.start(), parentRange.end()).trim();
                List<ChildChunk> childContents = splitRanges(parentContent, CHILD_CHUNK_SIZE, "TABLE".equals(normalizeChunkType(block.chunkType()))).stream()
                        .map(range -> new ChildChunk(
                                parentContent.substring(range.start(), range.end()).trim(),
                                block.pageNumber(),
                                normalizeChunkType(block.chunkType()),
                                blockIndex,
                                block.sectionPath(),
                                block.tableCaption(),
                                parentRange.start() + range.start(),
                                parentRange.start() + range.end()))
                        .filter(chunk -> !chunk.isEmpty())
                        .toList();

                if (!parentContent.isEmpty() && !childContents.isEmpty()) {
                    parents.add(new ParentChunk(
                            parentIndex,
                            parentContent,
                            block.pageNumber(),
                            normalizeChunkType(block.chunkType()),
                            blockIndex,
                            block.sectionPath(),
                            block.tableCaption(),
                            parentRange.start(),
                            parentRange.end(),
                            childContents));
                    parentIndex++;
                }
            }
            fallbackBlockIndex++;
        }

        return parents;
    }

    private List<TextRange> splitRanges(String content, int maxSize, boolean preserveLines) {
        List<TextRange> semanticUnits = semanticUnits(content, preserveLines);
        if (semanticUnits.size() == 1 && semanticUnits.getFirst().length() > maxSize) {
            return splitOversizedRange(content, semanticUnits.getFirst(), maxSize);
        }

        List<TextRange> ranges = new ArrayList<>();
        int start = -1;
        int end = -1;

        for (TextRange unit : semanticUnits) {
            if (unit.length() > maxSize) {
                if (start >= 0) {
                    ranges.add(new TextRange(start, end));
                    start = -1;
                    end = -1;
                }
                ranges.addAll(splitOversizedRange(content, unit, maxSize));
                continue;
            }

            if (start < 0) {
                start = unit.start();
                end = unit.end();
                continue;
            }

            if (unit.end() - start <= maxSize) {
                end = unit.end();
            } else {
                ranges.add(new TextRange(start, end));
                start = unit.start();
                end = unit.end();
            }
        }

        if (start >= 0) {
            ranges.add(new TextRange(start, end));
        }

        return ranges;
    }

    private List<TextRange> splitOversizedRange(String content, TextRange range, int maxSize) {
        List<TextRange> ranges = new ArrayList<>();
        int start = range.start();
        while (start < range.end()) {
            int end = Math.min(start + maxSize, range.end());
            if (end < range.end()) {
                end = findBreakPoint(content, start, end);
            }
            ranges.add(new TextRange(start, end));
            start = end;
        }
        return ranges;
    }

    private List<TextRange> semanticUnits(String content, boolean preserveLines) {
        List<TextRange> units = new ArrayList<>();
        int start = 0;
        int index = 0;

        while (index < content.length()) {
            char ch = content.charAt(index);
            boolean lineBreak = ch == '\n' || ch == '\r';
            boolean paragraphBreak = lineBreak && nextNonWhitespaceLineStartsAfterBlank(content, index);
            boolean sentenceBreak = isSentenceTerminator(ch) && nextLooksLikeBoundary(content, index + 1);
            boolean tableOrListBreak = preserveLines && lineBreak;

            if (paragraphBreak || tableOrListBreak || sentenceBreak) {
                int end = includeFollowingWhitespace(content, index + 1, paragraphBreak || tableOrListBreak);
                addUnit(units, content, start, end);
                start = end;
                index = end;
                continue;
            }
            index++;
        }

        addUnit(units, content, start, content.length());
        return units;
    }

    private int findBreakPoint(String content, int start, int end) {
        int minBreak = start + (end - start) / 2;
        for (int i = end - 1; i >= minBreak; i--) {
            char ch = content.charAt(i);
            if (Character.isWhitespace(ch)
                    || ch == '\n'
                    || ch == '\r'
                    || ch == '.'
                    || ch == '!'
                    || ch == '?'
                    || ch == ';'
                    || ch == ':'
                    || ch == ',') {
                return i + 1;
            }
        }
        return end;
    }

    private boolean nextNonWhitespaceLineStartsAfterBlank(String content, int index) {
        int cursor = index;
        int lineBreaks = 0;
        while (cursor < content.length()) {
            char ch = content.charAt(cursor);
            if (ch == '\n') {
                lineBreaks++;
            } else if (ch == '\r') {
                lineBreaks++;
                if (cursor + 1 < content.length() && content.charAt(cursor + 1) == '\n') {
                    cursor++;
                }
            } else if (!Character.isWhitespace(ch)) {
                return lineBreaks >= 2;
            }
            cursor++;
        }
        return false;
    }

    private boolean isSentenceTerminator(char ch) {
        return ch == '.' || ch == '!' || ch == '?' || ch == ';'
                || ch == '。' || ch == '！' || ch == '？' || ch == '；';
    }

    private boolean nextLooksLikeBoundary(String content, int index) {
        if (index >= content.length()) {
            return true;
        }
        char next = content.charAt(index);
        return Character.isWhitespace(next) || next == '\n' || next == '\r';
    }

    private int includeFollowingWhitespace(String content, int index, boolean includeNewLines) {
        int cursor = index;
        while (cursor < content.length()) {
            char ch = content.charAt(cursor);
            if (ch == '\n' || ch == '\r') {
                if (!includeNewLines) {
                    break;
                }
                cursor++;
                continue;
            }
            if (!Character.isWhitespace(ch)) {
                break;
            }
            cursor++;
        }
        return cursor;
    }

    private void addUnit(List<TextRange> units, String content, int start, int end) {
        int normalizedStart = start;
        int normalizedEnd = end;
        while (normalizedStart < normalizedEnd && Character.isWhitespace(content.charAt(normalizedStart))) {
            normalizedStart++;
        }
        while (normalizedEnd > normalizedStart && Character.isWhitespace(content.charAt(normalizedEnd - 1))) {
            normalizedEnd--;
        }
        if (normalizedStart < normalizedEnd) {
            units.add(new TextRange(normalizedStart, normalizedEnd));
        }
    }

    private String normalizeChunkType(String chunkType) {
        return StringUtils.hasText(chunkType) ? chunkType.trim().toUpperCase(Locale.ROOT) : "TEXT";
    }

    public record ParentChunk(
            int parentIndex,
            String content,
            Integer pageNumber,
            String chunkType,
            int blockIndex,
            List<String> sectionPath,
            String tableCaption,
            int startOffset,
            int endOffset,
            List<ChildChunk> children) {
    }

    public record ChildChunk(
            String content,
            Integer pageNumber,
            String chunkType,
            int blockIndex,
            List<String> sectionPath,
            String tableCaption,
            int startOffset,
            int endOffset) {

        boolean isEmpty() {
            return !StringUtils.hasText(content);
        }
    }

    private record TextRange(int start, int end) {

        int length() {
            return end - start;
        }
    }
}
