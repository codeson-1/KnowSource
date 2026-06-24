package com.knowsource.document;

public record ExtractedBlock(
        String content,
        Integer pageNumber,
        String chunkType) {
}
