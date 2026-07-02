package com.knowsource.document;

class DocumentExtractionException extends IllegalArgumentException {

    private final ExtractionQualityReport qualityReport;

    DocumentExtractionException(String message, ExtractionQualityReport qualityReport) {
        super(message);
        this.qualityReport = qualityReport == null ? ExtractionQualityReport.empty() : qualityReport;
    }

    DocumentExtractionException(String message, Throwable cause, ExtractionQualityReport qualityReport) {
        super(message, cause);
        this.qualityReport = qualityReport == null ? ExtractionQualityReport.empty() : qualityReport;
    }

    ExtractionQualityReport qualityReport() {
        return qualityReport;
    }
}
