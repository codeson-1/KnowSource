package com.knowsource.document;

import java.util.List;

public record ExtractedBlock(
        String content,
        Integer pageNumber,
        String chunkType,
        Integer blockIndex,
        List<String> sectionPath,
        String tableCaption) {

    public ExtractedBlock(String content, Integer pageNumber, String chunkType) {
        this(content, pageNumber, chunkType, null, List.of(), null);
    }

    public ExtractedBlock {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
    }
}
