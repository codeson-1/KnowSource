package com.knowsource.document;

import java.util.ArrayList;
import java.util.List;

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

        for (ExtractedBlock block : document.blocks()) {
            String content = block.content();
            if (!StringUtils.hasText(content)) {
                continue;
            }
            for (TextRange parentRange : splitRanges(content, PARENT_CHUNK_SIZE)) {
                String parentContent = content.substring(parentRange.start(), parentRange.end()).trim();
                List<ChildChunk> childContents = splitRanges(parentContent, CHILD_CHUNK_SIZE).stream()
                        .map(range -> parentContent.substring(range.start(), range.end()).trim())
                        .filter(chunk -> !chunk.isEmpty())
                        .map(chunk -> new ChildChunk(chunk, block.pageNumber(), normalizeChunkType(block.chunkType())))
                        .toList();

                if (!parentContent.isEmpty() && !childContents.isEmpty()) {
                    parents.add(new ParentChunk(
                            parentIndex,
                            parentContent,
                            block.pageNumber(),
                            normalizeChunkType(block.chunkType()),
                            childContents));
                    parentIndex++;
                }
            }
        }

        return parents;
    }

    private List<TextRange> splitRanges(String content, int maxSize) {
        List<TextRange> ranges = new ArrayList<>();
        int start = 0;

        while (start < content.length()) {
            int end = Math.min(start + maxSize, content.length());
            if (end < content.length()) {
                int breakAt = findBreakPoint(content, start, end);
                if (breakAt > start) {
                    end = breakAt;
                }
            }

            ranges.add(new TextRange(start, end));
            start = end;
        }

        return ranges;
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

    private String normalizeChunkType(String chunkType) {
        return StringUtils.hasText(chunkType) ? chunkType.trim().toUpperCase() : "TEXT";
    }

    public record ParentChunk(
            int parentIndex,
            String content,
            Integer pageNumber,
            String chunkType,
            List<ChildChunk> children) {
    }

    public record ChildChunk(String content, Integer pageNumber, String chunkType) {
    }

    private record TextRange(int start, int end) {
    }
}
