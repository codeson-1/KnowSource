package com.knowsource.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.xml.sax.SAXException;

@Component
public class PlainTextDocumentTextExtractor implements DocumentTextExtractor {

    private static final Set<String> PLAIN_TEXT_TYPES = Set.of("TEXT", "MARKDOWN");
    private static final Set<String> TIKA_TYPES = Set.of("PDF", "WORD");

    private final SourceStorageService sourceStorageService;
    private final AutoDetectParser parser = new AutoDetectParser();

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
        if (TIKA_TYPES.contains(fileType)) {
            return extractWithTika(sourceKey);
        }
        throw new IllegalArgumentException("Unsupported document file type: " + fileType);
    }

    private String extractWithTika(String sourceKey) throws IOException {
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, sourceKey);
        try (var inputStream = sourceStorageService.open(sourceKey)) {
            parser.parse(inputStream, handler, metadata);
            String extractedText = handler.toString();
            if (!StringUtils.hasText(extractedText)) {
                throw new IllegalArgumentException("Document source contains no extractable text.");
            }
            return extractedText;
        } catch (TikaException | SAXException ex) {
            throw new IllegalArgumentException("Failed to parse document source.", ex);
        }
    }
}
