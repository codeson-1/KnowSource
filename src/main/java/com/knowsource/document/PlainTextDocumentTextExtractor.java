package com.knowsource.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class PlainTextDocumentTextExtractor implements DocumentTextExtractor {

    private static final Set<String> PLAIN_TEXT_TYPES = Set.of("TEXT", "MARKDOWN");

    private final SourceStorageService sourceStorageService;

    public PlainTextDocumentTextExtractor(SourceStorageService sourceStorageService) {
        this.sourceStorageService = sourceStorageService;
    }

    @Override
    public String extract(String sourceKey, String fileType) throws IOException {
        if (PLAIN_TEXT_TYPES.contains(fileType)) {
            try (var inputStream = sourceStorageService.open(sourceKey)) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("Unsupported document file type: " + fileType);
    }
}
