package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DocumentService {
    private static final int TITLE_LIMIT = 255;
    private static final int CONTENT_LIMIT = 10000;
    private final AnalysisRepository analyses;
    private final PageRepository pages;
    private final UIElementRepository elements;
    private final ScreenshotRepository screenshots;
    private final DocumentRepository documents;
    private final DocumentSectionRepository sections;
    private final TransactionTemplate transactions;

    public DocumentService(AnalysisRepository analyses, PageRepository pages, UIElementRepository elements, ScreenshotRepository screenshots, DocumentRepository documents, DocumentSectionRepository sections, PlatformTransactionManager transactionManager) {
        this.analyses = analyses; this.pages = pages; this.elements = elements; this.screenshots = screenshots; this.documents = documents; this.sections = sections;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Document generate(String analysisId) {
        try {
            Document generated = transactions.execute(status -> generateInTransaction(analysisId));
            return generated;
        } catch (DataIntegrityViolationException duplicate) {
            return documents.findBySourceAnalysisId(analysisId).map(this::load).orElseThrow(() -> duplicate);
        }
    }

    private Document generateInTransaction(String analysisId) {
        Analysis analysis = analyses.findById(analysisId).orElseThrow(AnalysisNotFoundException::new);
        if (analysis.getStatus() != AnalysisStatus.COMPLETED) throw new AnalysisNotCompletedException();
        Optional<Document> existing = documents.findBySourceAnalysisId(analysisId);
        if (existing.isPresent()) return load(existing.get());
        Document document = documents.saveAndFlush(new Document(analysisId, analysis.getApplicationId()));
        List<Page> orderedPages = pages.findByAnalysisId(analysisId).stream().sorted(Comparator.comparing(Page::getUrl).thenComparing(Page::getId)).toList();
        for (int position = 0; position < orderedPages.size(); position++) {
            Page page = orderedPages.get(position);
            List<UIElement> pageElements = elements.findByPageId(page.getId()).stream().sorted(Comparator.comparing(UIElement::getKind).thenComparing(UIElement::getSelector).thenComparing(UIElement::getId)).toList();
            String content = pageElements.stream().map(e -> e.getKind() + ": " + (e.getAccessibleName() == null ? e.getSelector() : e.getAccessibleName()) + " [" + e.getSelector() + "]").collect(Collectors.joining("\n"));
            String title = page.getTitle() == null || page.getTitle().isBlank() ? page.getUrl() : page.getTitle();
            String screenshotId = screenshots.findByPageIdOrderByIdAsc(page.getId()).stream().findFirst().map(Screenshot::getId).orElse(null);
            DocumentSection section = sections.save(new DocumentSection(document.getId(), position, page.getId(), screenshotId, title, content));
            document.addSection(section);
        }
        return document;
    }

    public Document get(String documentId) { return documents.findById(documentId).map(this::load).orElseThrow(DocumentNotFoundException::new); }

    public Document update(String documentId, UpdateCommand command) {
        return transactions.execute(status -> updateInTransaction(documentId, command));
    }

    private Document updateInTransaction(String documentId, UpdateCommand command) {
        Document document = documents.findByIdForUpdate(documentId).orElseThrow(DocumentNotFoundException::new);
        List<DocumentSection> current = sections.findByDocumentIdOrderByPositionAsc(documentId);
        validate(command, documentId, current);
        Map<String, DocumentSection> byId = current.stream().collect(Collectors.toMap(DocumentSection::getId, s -> s));
        document.updateTitle(command.title());
        // Move all rows out of the final position range before assigning the new order.
        for (int i = 0; i < current.size(); i++) current.get(i).updateEditableFields(current.get(i).getTitle(), current.get(i).getContent(), -(i + 1));
        sections.saveAll(current);
        sections.flush();
        List<DocumentSection> reordered = new ArrayList<>();
        for (int position = 0; position < command.sections().size(); position++) {
            SectionUpdate requested = command.sections().get(position);
            DocumentSection section = byId.get(requested.id());
            section.updateEditableFields(requested.title(), requested.content(), position);
            reordered.add(section);
        }
        sections.saveAll(reordered);
        sections.flush();
        document.replaceSections(reordered);
        return document;
    }

    private void validate(UpdateCommand command, String documentId, List<DocumentSection> current) {
        if (command == null || blankOrTooLong(command.title(), TITLE_LIMIT)) throw new InvalidDocumentUpdateException("Document title is required and must be at most 255 characters");
        if (command.sections() == null || command.sections().size() != current.size()) throw new InvalidDocumentUpdateException("Sections must include exactly the document's current sections");
        Set<String> currentIds = current.stream().map(DocumentSection::getId).collect(Collectors.toSet());
        Set<String> requestedIds = new HashSet<>();
        for (SectionUpdate section : command.sections()) {
            if (section == null || !requestedIds.add(section.id()) || !currentIds.contains(section.id())) throw new InvalidDocumentUpdateException("Sections must contain unique IDs belonging to this document");
            if (blankOrTooLong(section.title(), TITLE_LIMIT)) throw new InvalidDocumentUpdateException("Section title is required and must be at most 255 characters");
            if (blankOrTooLong(section.content(), CONTENT_LIMIT)) throw new InvalidDocumentUpdateException("Section content is required and must be at most 10000 characters");
        }
    }

    private static boolean blankOrTooLong(String value, int limit) { return value == null || value.isBlank() || value.length() > limit; }
    private Document load(Document document) { document.replaceSections(sections.findByDocumentIdOrderByPositionAsc(document.getId())); return document; }
    public record UpdateCommand(String title, List<SectionUpdate> sections) {}
    public record SectionUpdate(String id, String title, String content) {}
    public static class AnalysisNotFoundException extends RuntimeException { public AnalysisNotFoundException() { super("Analysis not found"); } }
    public static class AnalysisNotCompletedException extends RuntimeException { public AnalysisNotCompletedException() { super("Documents can only be generated from completed analyses"); } }
    public static class DocumentNotFoundException extends RuntimeException { public DocumentNotFoundException() { super("Document not found"); } }
    public static class InvalidDocumentUpdateException extends RuntimeException { public InvalidDocumentUpdateException(String message) { super(message); } }
}
