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
            // A concurrent request won the source-analysis uniqueness race. Its transaction
            // is now committed, so the idempotent result can be loaded safely.
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
            String screenshotId = screenshots.findByPageId(page.getId()).map(Screenshot::getId).orElse(null);
            DocumentSection section = sections.save(new DocumentSection(document.getId(), position, page.getId(), screenshotId, title, content));
            document.addSection(section);
        }
        return document;
    }

    public Document get(String documentId) { return documents.findById(documentId).map(this::load).orElseThrow(DocumentNotFoundException::new); }
    private Document load(Document document) { document.replaceSections(sections.findByDocumentIdOrderByPositionAsc(document.getId())); return document; }
    public static class AnalysisNotFoundException extends RuntimeException { public AnalysisNotFoundException() { super("Analysis not found"); } }
    public static class AnalysisNotCompletedException extends RuntimeException { public AnalysisNotCompletedException() { super("Documents can only be generated from completed analyses"); } }
    public static class DocumentNotFoundException extends RuntimeException { public DocumentNotFoundException() { super("Document not found"); } }
}
