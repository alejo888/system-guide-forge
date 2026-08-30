package com.systemguideforge.backend.web;

import com.systemguideforge.backend.application.DocumentService;
import com.systemguideforge.backend.persistence.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
public class DocumentController {
    private final DocumentService service;
    public DocumentController(DocumentService service) { this.service = service; }

    @PostMapping("/api/analyses/{analysisId}/document")
    public ResponseEntity<DocumentResponse> generate(@PathVariable String analysisId) {
        Document document = service.generate(analysisId);
        return ResponseEntity.created(URI.create("/api/documents/" + document.getId())).body(to(document));
    }

    @GetMapping("/api/documents/{documentId}")
    public ResponseEntity<DocumentResponse> get(@PathVariable String documentId) {
        try { return ResponseEntity.ok(to(service.get(documentId))); }
        catch (DocumentService.DocumentNotFoundException e) { return ResponseEntity.notFound().build(); }
    }

    @ExceptionHandler(DocumentService.AnalysisNotFoundException.class)
    ResponseEntity<ErrorResponse> analysisNotFound(DocumentService.AnalysisNotFoundException e) { return ResponseEntity.notFound().build(); }
    @ExceptionHandler(DocumentService.AnalysisNotCompletedException.class)
    ResponseEntity<ErrorResponse> analysisNotCompleted(DocumentService.AnalysisNotCompletedException e) { return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage())); }

    private static DocumentResponse to(Document d) { return new DocumentResponse(d.getId(), d.getApplicationId(), d.getSourceAnalysisId(), d.getStatus(), d.getSections().stream().map(s -> new SectionResponse(s.getId(), s.getPosition(), s.getSourcePageId(), s.getScreenshotId(), s.getTitle(), s.getContent())).toList()); }
    public record DocumentResponse(String id, String applicationId, String sourceAnalysisId, Document.DocumentStatus status, java.util.List<SectionResponse> sections) {}
    public record SectionResponse(String id, int position, String sourcePageId, String screenshotId, String title, String content) {}
    public record ErrorResponse(String message) {}
}
