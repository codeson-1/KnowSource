package com.knowsource.document;

import java.util.List;

public record ExtractedDocument(List<ExtractedBlock> blocks) {

    public static ExtractedDocument text(String content) {
        return new ExtractedDocument(List.of(new ExtractedBlock(content, null, "TEXT")));
    }
}
