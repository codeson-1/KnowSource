package com.knowsource.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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

    private static final Set<String> TIKA_TYPES = Set.of("WORD");

    private final SourceStorageService sourceStorageService;
    private final MarkdownStructureParser markdownStructureParser;
    private final AutoDetectParser parser = new AutoDetectParser();

    public PlainTextDocumentTextExtractor(
            SourceStorageService sourceStorageService,
            MarkdownStructureParser markdownStructureParser) {
        this.sourceStorageService = sourceStorageService;
        this.markdownStructureParser = markdownStructureParser;
    }

    @Override
    public ExtractedDocument extract(String sourceKey, String fileType) throws IOException {
        if ("TEXT".equals(fileType)) {
            try (var inputStream = sourceStorageService.open(sourceKey)) {
                return ExtractedDocument.text(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        if ("MARKDOWN".equals(fileType)) {
            try (var inputStream = sourceStorageService.open(sourceKey)) {
                return markdownStructureParser.parse(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        if ("PDF".equals(fileType)) {
            return extractPdf(sourceKey);
        }
        if (TIKA_TYPES.contains(fileType)) {
            return extractWithTika(sourceKey);
        }
        throw new IllegalArgumentException("Unsupported document file type: " + fileType);
    }

    private ExtractedDocument extractPdf(String sourceKey) throws IOException {
        try (var inputStream = sourceStorageService.open(sourceKey);
                PDDocument document = PDDocument.load(inputStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<ExtractedBlock> blocks = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                addStructuredTextBlocks(blocks, stripper.getText(document), page);
            }
            if (blocks.isEmpty()) {
                throw new IllegalArgumentException("Document source contains no extractable text.");
            }
            return new ExtractedDocument(blocks);
        }
    }

    private ExtractedDocument extractWithTika(String sourceKey) throws IOException {
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, sourceKey);
        try (var inputStream = sourceStorageService.open(sourceKey)) {
            parser.parse(inputStream, handler, metadata);
            String extractedText = handler.toString();
            if (!StringUtils.hasText(extractedText)) {
                throw new IllegalArgumentException("Document source contains no extractable text.");
            }
            List<ExtractedBlock> blocks = new ArrayList<>();
            addStructuredTextBlocks(blocks, extractedText, null);
            return new ExtractedDocument(blocks);
        } catch (TikaException | SAXException ex) {
            throw new IllegalArgumentException("Failed to parse document source.", ex);
        }
    }

    private void addStructuredTextBlocks(List<ExtractedBlock> blocks, String text, Integer pageNumber) {
        for (String rawBlock : text.replace("\r\n", "\n").replace('\r', '\n').split("\\n\\s*\\n")) {
            String block = rawBlock.trim();
            if (!StringUtils.hasText(block)) {
                continue;
            }
            blocks.add(new ExtractedBlock(block, pageNumber, looksLikeTable(block) ? "TABLE" : "TEXT"));
        }
    }

    private boolean looksLikeTable(String block) {
        List<String> nonBlankLines = block.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        if (nonBlankLines.size() < 2) {
            return false;
        }
        long tableLikeRows = nonBlankLines.stream()
                .filter(line -> line.contains("|") || line.split("\\s{2,}").length >= 3 || line.split("\\t").length >= 3)
                .count();
        return tableLikeRows >= 2;
    }
}
