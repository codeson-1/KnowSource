package com.knowsource.document;

import java.util.List;

public record ExtractedDocument(List<ExtractedBlock> blocks, ExtractionQualityReport qualityReport) {

    public ExtractedDocument(List<ExtractedBlock> blocks) {
        this(blocks, ExtractionQualityReport.empty());
    }

    public ExtractedDocument {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        qualityReport = qualityReport == null ? ExtractionQualityReport.empty() : qualityReport;
    }

    public static ExtractedDocument text(String content) {
        return new ExtractedDocument(
                List.of(new ExtractedBlock(content, null, "TEXT", 0, List.of(), null, null)),
                ExtractionQualityReport.empty());
    }
}
