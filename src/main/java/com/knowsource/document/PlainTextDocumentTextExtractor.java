package com.knowsource.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
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
    private final LocalOcrService localOcrService;
    private final AutoDetectParser parser = new AutoDetectParser();

    public PlainTextDocumentTextExtractor(
            SourceStorageService sourceStorageService,
            MarkdownStructureParser markdownStructureParser,
            LocalOcrService localOcrService) {
        this.sourceStorageService = sourceStorageService;
        this.markdownStructureParser = markdownStructureParser;
        this.localOcrService = localOcrService;
    }

    @Override
    public ExtractedDocument extract(String sourceKey, String fileType) throws IOException {
        if ("TEXT".equals(fileType)) {
            try (var inputStream = sourceStorageService.open(sourceKey)) {
                String text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                List<ExtractedBlock> blocks = new ArrayList<>();
                addStructuredTextBlocks(blocks, text, null);
                if (blocks.isEmpty()) {
                    throw new DocumentExtractionException(
                            "Document source contains no extractable text.",
                            new ExtractionQualityReport(0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(),
                                    List.of("Plain text source was empty.")));
                }
                return new ExtractedDocument(blocks, qualityReport(0, blocks, List.of(), List.of(), List.of(), 0, List.of()));
            }
        }
        if ("MARKDOWN".equals(fileType)) {
            try (var inputStream = sourceStorageService.open(sourceKey)) {
                ExtractedDocument markdown = markdownStructureParser.parse(
                        new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
                return new ExtractedDocument(
                        markdown.blocks(),
                        qualityReport(0, markdown.blocks(), List.of(), List.of(), List.of(), 0, List.of()));
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
            PDFRenderer renderer = new PDFRenderer(document);
            List<ExtractedBlock> blocks = new ArrayList<>();
            List<Integer> emptyPages = new ArrayList<>();
            List<Integer> failedPages = new ArrayList<>();
            List<Integer> ocrRequiredPages = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            int ocrAppliedPages = 0;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                try {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    String pageText = stripper.getText(document);
                    if (!StringUtils.hasText(pageText)) {
                        emptyPages.add(page);
                        ocrRequiredPages.add(page);
                        if (localOcrService.enabled()) {
                            try {
                                var image = renderer.renderImageWithDPI(page - 1, 180, ImageType.RGB);
                                var ocrText = localOcrService.extractText(image);
                                if (ocrText.isPresent()) {
                                    addStructuredTextBlocks(blocks, ocrText.get(), page);
                                    ocrAppliedPages++;
                                    continue;
                                }
                            } catch (IOException | InterruptedException ex) {
                                if (ex instanceof InterruptedException) {
                                    Thread.currentThread().interrupt();
                                }
                                warnings.add("OCR failed on page " + page + ": " + ex.getClass().getSimpleName());
                            }
                        }
                        failedPages.add(page);
                        continue;
                    }
                    addStructuredTextBlocks(blocks, pageText, page);
                } catch (IOException | RuntimeException ex) {
                    failedPages.add(page);
                    warnings.add("PDF page " + page + " failed: " + ex.getClass().getSimpleName());
                }
            }
            ExtractionQualityReport report = qualityReport(
                    document.getNumberOfPages(), blocks, emptyPages, failedPages, ocrRequiredPages, ocrAppliedPages, warnings);
            if (blocks.isEmpty()) {
                throw new DocumentExtractionException("Document source contains no extractable text.", report);
            }
            return new ExtractedDocument(blocks, report);
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
                throw new DocumentExtractionException(
                        "Document source contains no extractable text.",
                        new ExtractionQualityReport(0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(),
                                List.of("Tika returned no text.")));
            }
            List<ExtractedBlock> blocks = new ArrayList<>();
            addStructuredTextBlocks(blocks, extractedText, null);
            return new ExtractedDocument(blocks, qualityReport(0, blocks, List.of(), List.of(), List.of(), 0, List.of()));
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
            ExtractedTable table = StructuredTableParser.parseIfTable(block);
            String chunkType = table != null ? "TABLE" : (looksLikeList(block) ? "LIST" : "TEXT");
            String content = table == null ? block : table.markdown();
            blocks.add(new ExtractedBlock(
                    content,
                    pageNumber,
                    chunkType,
                    blocks.size(),
                    List.of(),
                    "TABLE".equals(chunkType) ? firstNonBlankLine(block) : null,
                    table));
        }
    }

    private String firstNonBlankLine(String block) {
        return block.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private boolean looksLikeList(String block) {
        List<String> nonBlankLines = block.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        if (nonBlankLines.size() < 2) {
            return false;
        }
        long listRows = nonBlankLines.stream()
                .filter(line -> line.matches("^([-*+]\\s+|\\d+[.)]\\s+).+"))
                .count();
        return listRows >= 2;
    }

    private ExtractionQualityReport qualityReport(
            int pageCount,
            List<ExtractedBlock> blocks,
            List<Integer> emptyPages,
            List<Integer> failedPages,
            List<Integer> ocrRequiredPages,
            int ocrAppliedPages,
            List<String> warnings) {
        int tableCount = (int) blocks.stream()
                .filter(block -> "TABLE".equalsIgnoreCase(block.chunkType()))
                .count();
        int structuredTableCount = (int) blocks.stream()
                .filter(block -> block.table() != null)
                .count();
        int extractedPageCount = pageCount > 0
                ? (int) blocks.stream()
                        .map(ExtractedBlock::pageNumber)
                        .filter(page -> page != null && page > 0)
                        .distinct()
                        .count()
                : 0;
        return new ExtractionQualityReport(
                pageCount,
                extractedPageCount,
                emptyPages.size(),
                tableCount,
                structuredTableCount,
                failedPages.size(),
                ocrRequiredPages.size(),
                ocrAppliedPages,
                emptyPages,
                failedPages,
                ocrRequiredPages,
                warnings);
    }
}
