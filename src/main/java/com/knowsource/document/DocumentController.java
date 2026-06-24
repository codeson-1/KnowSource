package com.knowsource.document;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/kbs/{kbId}/documents")
    public ResponseEntity<DocumentIngestResponse> ingest(
            @PathVariable String kbId,
            @RequestBody CreateDocumentRequest request) {
        DocumentIngestResponse response = documentService.ingest(kbId, request);
        return ResponseEntity.created(URI.create("/api/documents/" + response.document().id())).body(response);
    }

    @PostMapping(value = "/kbs/{kbId}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentIngestResponse> upload(
            @PathVariable String kbId,
            @RequestParam String title,
            @RequestParam("file") MultipartFile file) {
        DocumentIngestResponse response = documentService.upload(kbId, title, file);
        return ResponseEntity.created(URI.create("/api/documents/" + response.document().id())).body(response);
    }

    @PutMapping("/documents/{docId}")
    public ResponseEntity<DocumentIngestResponse> replace(
            @PathVariable String docId,
            @RequestBody ReplaceDocumentRequest request) {
        return ResponseEntity.accepted().body(documentService.replace(docId, request));
    }

    @PostMapping(value = "/documents/{docId}/replace-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentIngestResponse> replaceUpload(
            @PathVariable String docId,
            @RequestParam String title,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.accepted().body(documentService.replaceUpload(docId, title, file));
    }

    @GetMapping("/kbs/{kbId}/documents")
    public List<DocumentResponse> listByKnowledgeBase(@PathVariable String kbId) {
        return documentService.listByKnowledgeBase(kbId);
    }

    @GetMapping("/documents/{docId}")
    public DocumentResponse getDocument(@PathVariable String docId) {
        return documentService.getDocument(docId);
    }

    @GetMapping("/documents/{docId}/chunks")
    public List<DocumentChunkResponse> listChunks(@PathVariable String docId) {
        return documentService.listChunks(docId);
    }

    @GetMapping("/documents/{docId}/ingest-task")
    public DocumentIngestResponse getLatestIngestTask(@PathVariable String docId) {
        return documentService.getLatestIngestTask(docId);
    }

    @PostMapping("/documents/{docId}/ingest-task/retry")
    public ResponseEntity<DocumentIngestResponse> retryLatestIngestTask(@PathVariable String docId) {
        return ResponseEntity.accepted().body(documentService.retryLatestIngestTask(docId));
    }

    @PostMapping("/documents/{docId}/publish")
    public ResponseEntity<DocumentPublishResponse> publish(@PathVariable String docId) {
        return ResponseEntity.accepted().body(documentService.publish(docId));
    }

    @PostMapping("/documents/{docId}/archive")
    public ResponseEntity<DocumentPublishResponse> archive(@PathVariable String docId) {
        return ResponseEntity.accepted().body(documentService.archive(docId));
    }

    @GetMapping("/documents/{docId}/preview")
    public DocumentPreviewResponse preview(
            @PathVariable String docId,
            @RequestParam(required = false) Integer pageNumber) {
        return documentService.preview(docId, pageNumber);
    }

    @GetMapping(value = "/documents/source-preview", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<org.springframework.core.io.InputStreamResource> sourcePreview(@RequestParam String sourceKey)
            throws java.io.IOException {
        return ResponseEntity.ok(new org.springframework.core.io.InputStreamResource(documentService.openSourcePreview(sourceKey)));
    }

    @PostMapping("/documents/{docId}/index-events/{eventId}/requeue")
    public ResponseEntity<DocumentPublishResponse> requeueIndexEvent(
            @PathVariable String docId,
            @PathVariable String eventId) {
        return ResponseEntity.accepted().body(documentService.requeueIndexEvent(docId, eventId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(java.io.IOException.class)
    public ResponseEntity<Map<String, String>> handleSourceReadFailure(java.io.IOException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Document source not found."));
    }
}
