package com.systemguideforge.backend.web;

import com.systemguideforge.backend.application.DocumentService;
import com.systemguideforge.backend.application.ManualExporter;
import com.systemguideforge.backend.persistence.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.Locale;

@RestController
public class DocumentController {
    private static final MediaType DOCX_MEDIA_TYPE = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private final DocumentService service;
    private final ManualExporter exporter;
    public DocumentController(DocumentService service, ManualExporter exporter) { this.service = service; this.exporter = exporter; }

    @PostMapping("/api/analyses/{analysisId}/document")
    public ResponseEntity<DocumentResponse> generate(@PathVariable String analysisId, @RequestBody(required = false) GenerateRequest request) {
        if (request == null || request.language() == null || request.language().isBlank() || request.type() == null || request.type().isBlank()) throw new DocumentService.InvalidGenerationRequestException("Document language and type are required");
        Document.DocumentLanguage language;
        Document.DocumentType type;
        try { language = Document.DocumentLanguage.valueOf(request.language().trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw new DocumentService.InvalidGenerationRequestException("Document language must be en or es"); }
        try { type = Document.DocumentType.valueOf(request.type().trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw new DocumentService.InvalidGenerationRequestException("Unsupported document type"); }
        Document document = service.generate(analysisId, language, type, Boolean.TRUE.equals(request.confirmReplacement()));
        return ResponseEntity.created(URI.create("/api/documents/" + document.getId())).body(to(document));
    }
    @GetMapping("/api/documents/{documentId}")
    public ResponseEntity<DocumentResponse> get(@PathVariable String documentId) { try { return ResponseEntity.ok(to(service.get(documentId))); } catch (DocumentService.DocumentNotFoundException e) { return ResponseEntity.notFound().build(); } }
    @GetMapping("/api/documents/{documentId}/export")
    public ResponseEntity<?> export(@PathVariable String documentId, @RequestParam(required = false) String format) {
        if (!"docx".equals(format)) return ResponseEntity.badRequest().body(new ErrorResponse("Only docx export format is supported"));
        try {
            ManualExporter.ExportedManual exported = exporter.export(documentId);
            return ResponseEntity.ok()
                    .contentType(DOCX_MEDIA_TYPE)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename(exported.title()))
                    .body(exported.bytes());
        } catch (DocumentService.DocumentNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }
    @PutMapping("/api/documents/{documentId}")
    public ResponseEntity<DocumentResponse> update(@PathVariable String documentId, @RequestBody(required = false) UpdateRequest request) {
        try { if (request == null) throw new DocumentService.InvalidDocumentUpdateException("Document update request is required"); DocumentService.UpdateCommand command = new DocumentService.UpdateCommand(request.title(), request.sections() == null ? null : request.sections().stream().map(s -> s == null ? null : new DocumentService.SectionUpdate(s.id(), s.title(), s.content(), s.hidden())).toList()); return ResponseEntity.ok(to(service.update(documentId, command))); } catch (DocumentService.DocumentNotFoundException e) { return ResponseEntity.notFound().build(); }
    }
    @ExceptionHandler(DocumentService.AnalysisNotFoundException.class) ResponseEntity<ErrorResponse> analysisNotFound(DocumentService.AnalysisNotFoundException e) { return ResponseEntity.notFound().build(); }
    @ExceptionHandler(DocumentService.AnalysisNotCompletedException.class) ResponseEntity<ErrorResponse> analysisNotCompleted(DocumentService.AnalysisNotCompletedException e) { return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage())); }
    @ExceptionHandler(DocumentService.DraftReplacementConfirmationRequiredException.class) ResponseEntity<ErrorResponse> draftReplacementConfirmationRequired(DocumentService.DraftReplacementConfirmationRequiredException e) { return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage(), "DRAFT_REPLACEMENT_CONFIRMATION_REQUIRED")); }
    @ExceptionHandler({DocumentService.InvalidDocumentUpdateException.class, DocumentService.InvalidGenerationRequestException.class}) ResponseEntity<ErrorResponse> invalidRequest(RuntimeException e) { return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage())); }
    ResponseEntity<ErrorResponse> invalidUpdate(DocumentService.InvalidDocumentUpdateException e) { return invalidRequest(e); }
    private static String filename(String title) {
        String safeTitle = title == null ? "manual" : title.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return (safeTitle.isBlank() ? "manual" : safeTitle) + ".docx";
    }
    private static DocumentResponse to(Document d) { return new DocumentResponse(d.getId(), d.getApplicationId(), d.getSourceAnalysisId(), d.getTitle(), d.getStatus(), d.getLanguage().name().toLowerCase(Locale.ROOT), d.getType().name().toLowerCase(Locale.ROOT), d.getSections().stream().map(s -> new SectionResponse(s.getId(), s.getPosition(), s.getSourcePageId(), s.getScreenshotId(), s.getTitle(), s.getContent(), s.isHidden())).toList()); }
    public record DocumentResponse(String id, String applicationId, String sourceAnalysisId, String title, Document.DocumentStatus status, String language, String type, java.util.List<SectionResponse> sections) {}
    public record SectionResponse(String id, int position, String sourcePageId, String screenshotId, String title, String content, boolean hidden) {}
    public record GenerateRequest(String language, String type, Boolean confirmReplacement) {
        public GenerateRequest(String language, String type) { this(language, type, false); }
    }
    public record UpdateRequest(String title, java.util.List<SectionUpdateRequest> sections) {}
    public record SectionUpdateRequest(String id, String title, String content, boolean hidden) {
        public SectionUpdateRequest(String id, String title, String content) { this(id, title, content, false); }
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String message, String code) {
        public ErrorResponse(String message) { this(message, null); }
    }
}
