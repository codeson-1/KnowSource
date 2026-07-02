package com.knowsource.document;

import java.util.List;

public record DocumentChunkResponse(
        String id,
        String docId,
        int docVersion,
        String parentChunkId,
        String content,
        int chunkIndex,
        Integer pageNumber,
        String chunkType,
        List<String> sectionPath,
        String tableCaption,
        Integer startOffset,
        Integer endOffset,
        String tableMarkdown,
        Integer tableRowCount,
        Integer tableColumnCount) {

    public DocumentChunkResponse {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
    }
}
