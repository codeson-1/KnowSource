package com.knowsource.document;

import java.util.List;

public record ExtractionQualityReport(
        int pageCount,
        int extractedPageCount,
        int emptyPageCount,
        int tableCount,
        int structuredTableCount,
        int failedPageCount,
        int ocrRequiredPageCount,
        int ocrAppliedPageCount,
        List<Integer> emptyPages,
        List<Integer> failedPages,
        List<Integer> ocrRequiredPages,
        List<String> warnings) {

    public ExtractionQualityReport {
        emptyPages = emptyPages == null ? List.of() : List.copyOf(emptyPages);
        failedPages = failedPages == null ? List.of() : List.copyOf(failedPages);
        ocrRequiredPages = ocrRequiredPages == null ? List.of() : List.copyOf(ocrRequiredPages);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ExtractionQualityReport empty() {
        return new ExtractionQualityReport(0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of());
    }
}
