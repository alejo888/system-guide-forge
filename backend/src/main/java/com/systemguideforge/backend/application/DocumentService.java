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
    private final TargetApplicationRepository applications;
    private final TransactionTemplate transactions;
    private final FunctionalModuleDeriver moduleDeriver = new FunctionalModuleDeriver();

    public DocumentService(AnalysisRepository analyses, PageRepository pages, UIElementRepository elements, ScreenshotRepository screenshots, DocumentRepository documents, DocumentSectionRepository sections, TargetApplicationRepository applications, PlatformTransactionManager transactionManager) {
        this.analyses = analyses; this.pages = pages; this.elements = elements; this.screenshots = screenshots; this.documents = documents; this.sections = sections; this.applications = applications;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Document generate(String analysisId) { return generate(analysisId, Document.DocumentLanguage.EN, Document.DocumentType.USER_MANUAL); }
    public Document generate(String analysisId, Document.DocumentLanguage language) { return generate(analysisId, language, Document.DocumentType.USER_MANUAL); }
    public Document generate(String analysisId, Document.DocumentLanguage language, Document.DocumentType type) {
        validateGeneration(analysisId, language, type);
        try { return transactions.execute(status -> generateInTransaction(analysisId, language, type)); }
        catch (DataIntegrityViolationException duplicate) {
            return transactions.execute(status -> generateInTransaction(analysisId, language, type));
        }
    }

    private void validateGeneration(String analysisId, Document.DocumentLanguage language, Document.DocumentType type) {
        if (analysisId == null || analysisId.isBlank()) throw new InvalidGenerationRequestException("Analysis ID is required");
        if (language == null) throw new InvalidGenerationRequestException("Document language is required");
        if (type == null) throw new InvalidGenerationRequestException("Document type is required");
    }

    private Document generateInTransaction(String analysisId, Document.DocumentLanguage language, Document.DocumentType type) {
        Analysis analysis = analyses.findById(analysisId).orElseThrow(AnalysisNotFoundException::new);
        if (analysis.getStatus() != AnalysisStatus.COMPLETED) throw new AnalysisNotCompletedException();
        Optional<Document> existing = documents.findBySourceAnalysisId(analysisId);
        if (existing.isPresent() && language == existing.get().getLanguage() && type == existing.get().getType()) return load(existing.get());
        Document document = existing.orElseGet(() -> {
            String applicationId = analysis.getApplicationId();
            return documents.saveAndFlush(new Document(analysisId, applicationId, language, type));
        });
        if (existing.isPresent()) {
            sections.deleteAll(sections.findByDocumentIdOrderByPositionAsc(document.getId()));
            sections.flush();
            document.replaceSections(new ArrayList<>());
            document.updateGeneration(language, type);
        }
        String applicationName = applications.findById(analysis.getApplicationId()).orElseThrow(ApplicationNotFoundException::new).getName();
        document.updateTitle(boundedText(documentTitle(type, applicationName, language), TITLE_LIMIT));
        List<Page> sourcePages = pages.findByAnalysisId(analysisId);
        Map<String, Page> pagesById = sourcePages.stream().collect(Collectors.toMap(Page::getId, page -> page));
        List<Page> orderedPages = moduleDeriver.derive(sourcePages).stream().flatMap(module -> module.pages().stream()).map(modulePage -> pagesById.get(modulePage.id())).toList();
        for (int position = 0; position < orderedPages.size(); position++) {
            Page page = orderedPages.get(position);
            List<UIElement> pageElements = elements.findByPageId(page.getId()).stream().sorted(Comparator.comparing(UIElement::getKind, Comparator.nullsFirst(String::compareTo)).thenComparing(UIElement::getSelector, Comparator.nullsFirst(String::compareTo)).thenComparing(UIElement::getId)).toList();
            String content = describePage(page, pageElements, language);
            String title = boundedText(descriptiveTitle(page, language), TITLE_LIMIT);
            String screenshotId = screenshots.findByPageIdOrderByIdAsc(page.getId()).stream().findFirst().map(Screenshot::getId).orElse(null);
            DocumentSection section = sections.save(new DocumentSection(document.getId(), position, page.getId(), screenshotId, title, content));
            document.addSection(section);
        }
        return document;
    }

    private String descriptiveTitle(Page page, Document.DocumentLanguage language) {
        String pageTitle = pageTitle(page, language);
        return moduleDeriver.moduleNameFor(page.getUrl()) + ": " + pageTitle + " (" + moduleDeriver.routeFor(page.getUrl()) + ")";
    }
    private String pageTitle(Page page, Document.DocumentLanguage language) { return page.getTitle() == null || page.getTitle().isBlank() ? (language == Document.DocumentLanguage.ES ? "Página sin título" : "Untitled page") : page.getTitle().trim(); }
    private String documentTitle(Document.DocumentType type, String applicationName, Document.DocumentLanguage language) { return (type == Document.DocumentType.USER_MANUAL ? (language == Document.DocumentLanguage.ES ? "Manual de usuario" : "User manual") : type.name()) + " \"" + boundedText(applicationName, 200) + "\""; }

    private String describePage(Page page, List<UIElement> pageElements, Document.DocumentLanguage language) {
        boolean spanish = language == Document.DocumentLanguage.ES;
        StringBuilder result = new StringBuilder();
        result.append(spanish ? "Propósito: usa esta guía para consultar la pantalla y seguir solo las instrucciones permitidas por la clasificación observada.\n\nEsta pantalla sirve para consultar la sección \"" : "Purpose: use this guide to review the screen and follow only instructions permitted by the observed classification.\n\nThis screen is for reviewing the \"")
                .append(boundedText(pageTitle(page, language), 3000)).append(spanish ? "\" (ruta: " : "\" (route: ").append(boundedText(moduleDeriver.routeFor(page.getUrl()), 3000)).append(").\n\n")
                .append(spanish ? "Pasos seguros para usarla:\n" : "Safe usage steps:\n");
        if (pageElements.isEmpty()) result.append(spanish ? "1. No hay controles interactivos observados; usa la pantalla solo para consultar la información visible.\n" : "1. No interactive controls were observed; use this screen only to review visible information.\n");
        else { int step = 1; for (UIElement element : pageElements) { String instruction = instructionFor(element, step, spanish); if (result.length() + instruction.length() + 1 > CONTENT_LIMIT) break; result.append(instruction).append('\n'); step++; } }
        result.append('\n').append(spanish ? "Seguridad y límites:\nSAFE: la evidencia observada permite consultar el control; en los enlaces también permite navegar.\nMUTATING: no ejecutes la acción porque puede modificar datos.\nUNKNOWN: verifica manualmente el efecto antes de usar o completar el control; no se considera seguro por la evidencia observada." : "Safety and limitations:\nSAFE: the observed evidence permits reviewing the control; links may also be opened for navigation.\nMUTATING: do not execute the action because it may change data.\nUNKNOWN: verify the effect manually before using or filling the control; it is not considered safe from the observed evidence.");
        return boundedText(result.toString(), CONTENT_LIMIT);
    }
    private String instructionFor(UIElement element, int step, boolean spanish) {
        String name = "\"" + boundedText(displayName(element), 3000) + "\""; boolean input = isInput(element.getKind()); boolean link = isLink(element.getKind()); String instruction;
        switch (classification(element)) {
            case SAFE -> instruction = input ? (spanish ? "Puedes consultar el campo " + name + " sin completarlo." : "You can review the field " + name + " without filling it.") : link ? (spanish ? "Puedes seleccionar o abrir el enlace " + name + " para navegar o consultarlo." : "You can select or open the link " + name + " to navigate or consult it.") : (spanish ? "Puedes usar o seleccionar el control " + name + " para una consulta segura." : "You can use or select the control " + name + " for safe consultation.");
            case MUTATING -> instruction = input ? (spanish ? "No completes ni uses el campo " + name + " porque puede cambiar datos." : "Do not fill or use the field " + name + " because it may change data.") : (spanish ? "No ejecutes la acción del control " + name + " porque puede cambiar datos." : "Do not execute the action of the control " + name + " because it may change data.");
            case UNKNOWN -> instruction = input ? (spanish ? "Verifica manualmente el efecto del campo " + name + " antes de completarlo o usarlo; no se considera seguro." : "Verify the effect of the field " + name + " manually before filling or using it; it is not considered safe.") : (spanish ? "Verifica manualmente el efecto del control " + name + " antes de usarlo; no se considera seguro." : "Verify the effect of the control " + name + " manually before using it; it is not considered safe.");
            default -> throw new IllegalStateException("Unsupported action classification");
        }
        return step + ". " + instruction + (spanish ? " Referencia técnica: clasificación " : " Technical reference: classification ") + classification(element) + (spanish ? "; selector: \"" : "; selector: \"") + boundedText(element.getSelector(), 3000) + "\".";
    }
    private boolean isLink(String kind) { return kind != null && kind.equalsIgnoreCase("link"); }
    private boolean isInput(String kind) { return kind != null && (kind.equalsIgnoreCase("input") || kind.equalsIgnoreCase("textarea")); }
    private String displayName(UIElement element) { return element.getAccessibleName() != null && !element.getAccessibleName().isBlank() ? element.getAccessibleName() : element.getSelector(); }
    private ActionClassification classification(UIElement element) { return element.getActionClassification() == null ? ActionClassification.UNKNOWN : element.getActionClassification(); }
    private static String boundedText(String value, int limit) { if (value == null) return ""; if (value.length() <= limit) return value; if (limit <= 3) return value.substring(0, limit); int prefixLength = (limit - 3) / 2; return value.substring(0, prefixLength) + "..." + value.substring(value.length() - (limit - 3 - prefixLength)); }
    public Document get(String documentId) { return documents.findById(documentId).map(this::load).orElseThrow(DocumentNotFoundException::new); }
    public Document update(String documentId, UpdateCommand command) { return transactions.execute(status -> updateInTransaction(documentId, command)); }
    private Document updateInTransaction(String documentId, UpdateCommand command) { Document document = documents.findByIdForUpdate(documentId).orElseThrow(DocumentNotFoundException::new); List<DocumentSection> current = sections.findByDocumentIdOrderByPositionAsc(documentId); validate(command, current); Map<String, DocumentSection> byId = current.stream().collect(Collectors.toMap(DocumentSection::getId, s -> s)); document.updateTitle(command.title()); for (int i = 0; i < current.size(); i++) current.get(i).updateEditableFields(current.get(i).getTitle(), current.get(i).getContent(), -(i + 1)); sections.saveAll(current); sections.flush(); List<DocumentSection> reordered = new ArrayList<>(); for (int position = 0; position < command.sections().size(); position++) { SectionUpdate requested = command.sections().get(position); DocumentSection section = byId.get(requested.id()); section.updateEditableFields(requested.title(), requested.content(), position); reordered.add(section); } sections.saveAll(reordered); sections.flush(); document.replaceSections(reordered); return document; }
    private void validate(UpdateCommand command, List<DocumentSection> current) { if (command == null || blankOrTooLong(command.title(), TITLE_LIMIT)) throw new InvalidDocumentUpdateException("Document title is required and must be at most 255 characters"); if (command.sections() == null || command.sections().size() != current.size()) throw new InvalidDocumentUpdateException("Sections must include exactly the document's current sections"); Set<String> ids = current.stream().map(DocumentSection::getId).collect(Collectors.toSet()); Set<String> requested = new HashSet<>(); for (SectionUpdate section : command.sections()) { if (section == null || section.id() == null || !requested.add(section.id()) || !ids.contains(section.id())) throw new InvalidDocumentUpdateException("Sections must contain unique IDs belonging to this document"); if (blankOrTooLong(section.title(), TITLE_LIMIT)) throw new InvalidDocumentUpdateException("Section title is required and must be at most 255 characters"); if (blankOrTooLong(section.content(), CONTENT_LIMIT)) throw new InvalidDocumentUpdateException("Section content is required and must be at most 10000 characters"); } }
    private static boolean blankOrTooLong(String value, int limit) { return value == null || value.isBlank() || value.length() > limit; }
    private Document load(Document document) { document.replaceSections(sections.findByDocumentIdOrderByPositionAsc(document.getId())); return document; }
    public record UpdateCommand(String title, List<SectionUpdate> sections) {}
    public record SectionUpdate(String id, String title, String content) {}
    public static class AnalysisNotFoundException extends RuntimeException { public AnalysisNotFoundException() { super("Analysis not found"); } }
    public static class ApplicationNotFoundException extends RuntimeException { public ApplicationNotFoundException() { super("Application not found"); } }
    public static class AnalysisNotCompletedException extends RuntimeException { public AnalysisNotCompletedException() { super("Documents can only be generated from completed analyses"); } }
    public static class InvalidGenerationRequestException extends RuntimeException { public InvalidGenerationRequestException(String message) { super(message); } }
    public static class DocumentNotFoundException extends RuntimeException { public DocumentNotFoundException() { super("Document not found"); } }
    public static class InvalidDocumentUpdateException extends RuntimeException { public InvalidDocumentUpdateException(String message) { super(message); } }
}
