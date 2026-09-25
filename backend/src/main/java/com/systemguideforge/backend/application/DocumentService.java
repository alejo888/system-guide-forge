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
    public Document generate(String analysisId, Document.DocumentLanguage language, Document.DocumentType type) { return generate(analysisId, language, type, false); }
    public Document generate(String analysisId, Document.DocumentLanguage language, Document.DocumentType type, boolean confirmReplacement) {
        validateGeneration(analysisId, language, type);
        try { return transactions.execute(status -> generateInTransaction(analysisId, language, type, confirmReplacement)); }
        catch (DataIntegrityViolationException duplicate) {
            return transactions.execute(status -> generateInTransaction(analysisId, language, type, confirmReplacement));
        }
    }

    private void validateGeneration(String analysisId, Document.DocumentLanguage language, Document.DocumentType type) {
        if (analysisId == null || analysisId.isBlank()) throw new InvalidGenerationRequestException("Analysis ID is required");
        if (language == null) throw new InvalidGenerationRequestException("Document language is required");
        if (type == null) throw new InvalidGenerationRequestException("Document type is required");
    }

    private Document generateInTransaction(String analysisId, Document.DocumentLanguage language, Document.DocumentType type, boolean confirmReplacement) {
        Analysis analysis = analyses.findById(analysisId).orElseThrow(AnalysisNotFoundException::new);
        if (analysis.getStatus() != AnalysisStatus.COMPLETED) throw new AnalysisNotCompletedException();
        Optional<Document> existing = documents.findBySourceAnalysisId(analysisId);
        if (existing.isPresent() && language == existing.get().getLanguage() && type == existing.get().getType() && !isLegacyTechnicalUserManual(existing.get())) return load(existing.get());
        if (existing.isPresent() && !confirmReplacement) throw new DraftReplacementConfirmationRequiredException();
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
        if (page.getKind() == PageKind.LOGIN) return language == Document.DocumentLanguage.ES ? "Cómo ingresar al sistema" : "How to sign in";
        String pageTitle = pageTitle(page, language);
        return moduleDeriver.moduleNameFor(page.getUrl()) + ": " + pageTitle + " (" + moduleDeriver.routeFor(page.getUrl()) + ")";
    }
    private String pageTitle(Page page, Document.DocumentLanguage language) { return page.getTitle() == null || page.getTitle().isBlank() ? (language == Document.DocumentLanguage.ES ? "Página sin título" : "Untitled page") : page.getTitle().trim(); }
    private String documentTitle(Document.DocumentType type, String applicationName, Document.DocumentLanguage language) { return (type == Document.DocumentType.USER_MANUAL ? (language == Document.DocumentLanguage.ES ? "Manual de usuario" : "User manual") : type.name()) + " \"" + boundedText(applicationName, 200) + "\""; }

    private String describePage(Page page, List<UIElement> pageElements, Document.DocumentLanguage language) {
        boolean spanish = language == Document.DocumentLanguage.ES;
        StringBuilder result = new StringBuilder();
        result.append(pageIntroduction(page, language)).append("\n\n")
                .append(spanish ? "Pasos:\n" : "Steps:\n");
        int step = 1;
        for (InstructionGroup group : InstructionGroup.values()) {
            List<UIElement> groupElements = pageElements.stream()
                    .filter(this::isIncludedInManual)
                    .filter(element -> instructionGroupFor(element) == group)
                    .toList();
            boolean groupStarted = false;
            for (UIElement element : groupElements) {
                String instruction = instructionFor(element, step, spanish);
                String prefix = groupStarted ? "" : instructionGroupTitle(group, spanish) + "\n";
                if (result.length() + prefix.length() + instruction.length() + 1 > CONTENT_LIMIT) return boundedText(result.toString(), CONTENT_LIMIT);
                result.append(prefix).append(instruction).append('\n');
                groupStarted = true;
                step++;
            }
        }
        if (step == 1) result.append(spanish ? "No se identificaron acciones para documentar; consultá la información visible en esta pantalla.\n" : "No actions were identified for this guide; review the visible information on this screen.\n");
        return boundedText(result.toString(), CONTENT_LIMIT);
    }
    private String pageIntroduction(Page page, Document.DocumentLanguage language) {
        if (page.getKind() == PageKind.LOGIN) {
            return language == Document.DocumentLanguage.ES
                    ? "Esta pantalla te permite ingresar al sistema. Ingresá tu usuario o correo electrónico y tu contraseña en el formulario y luego presioná el botón de inicio de sesión."
                    : "This screen lets you sign in to the application. Enter your username or email and your password in the form, then press the sign-in button.";
        }
        String title = boundedText(pageTitle(page, language), 3000);
        String route = boundedText(moduleDeriver.routeFor(page.getUrl()), 3000);
        return language == Document.DocumentLanguage.ES
                ? "La ruta " + route + " muestra la página \"" + title + "\"."
                : "The " + route + " route displays the \"" + title + "\" page.";
    }
    private InstructionGroup instructionGroupFor(UIElement element) {
        return switch (normalizedKind(element.getKind())) {
            case "link" -> InstructionGroup.NAVIGATION;
            case "input", "textarea", "select", "dropdown", "combobox" -> InstructionGroup.INFORMATION;
            default -> InstructionGroup.ACTIONS;
        };
    }
    private String instructionGroupTitle(InstructionGroup group, boolean spanish) {
        return switch (group) {
            case NAVIGATION -> spanish ? "Navegación:" : "Navigation:";
            case INFORMATION -> spanish ? "Información:" : "Information:";
            case ACTIONS -> spanish ? "Acciones:" : "Actions:";
        };
    }
    private String instructionFor(UIElement element, int step, boolean spanish) {
        String name = "\"" + boundedText(displayName(element, spanish), 3000) + "\"";
        String instruction = switch (normalizedKind(element.getKind())) {
            case "link" -> spanish ? "Abrí el enlace " + name + " para continuar." : "Open the link " + name + " to continue.";
            case "button", "submit" -> spanish ? "Seleccioná el botón " + name + " para continuar." : "Select the button " + name + " to continue.";
            case "input" -> spanish ? "Ingresá la información en el campo " + name + "." : "Enter the information in the field " + name + ".";
            case "textarea" -> spanish ? "Escribí la información en el campo " + name + "." : "Write the information in the field " + name + ".";
            case "select", "dropdown", "combobox" -> spanish ? "Elegí una opción en " + name + "." : "Choose an option in " + name + ".";
            case "checkbox" -> spanish ? "Marcá la opción " + name + "." : "Select the option " + name + ".";
            case "radio" -> spanish ? "Seleccioná la opción " + name + "." : "Select the option " + name + ".";
            default -> spanish ? "Usá el control " + name + " para continuar." : "Use the control " + name + " to continue.";
        };
        return step + ". " + instruction;
    }
    private boolean isIncludedInManual(UIElement element) { return classification(element) == ActionClassification.SAFE || (classification(element) == ActionClassification.UNKNOWN && element.isManualInclusionApproved()); }
    private enum InstructionGroup { NAVIGATION, INFORMATION, ACTIONS }
    private boolean isLegacyTechnicalUserManual(Document document) {
        if (document.getType() != Document.DocumentType.USER_MANUAL) return false;
        return sections.findByDocumentIdOrderByPositionAsc(document.getId()).stream()
                .map(DocumentSection::getContent)
                .anyMatch(content -> content != null && (content.contains("Technical reference: classification") || content.contains("Referencia técnica: clasificación")));
    }
    private String normalizedKind(String kind) { return kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT); }
    private String displayName(UIElement element, boolean spanish) { return element.getAccessibleName() != null && !element.getAccessibleName().isBlank() ? element.getAccessibleName() : (spanish ? "este control" : "this control"); }
    private ActionClassification classification(UIElement element) { return element.getActionClassification() == null ? ActionClassification.UNKNOWN : element.getActionClassification(); }
    private static String boundedText(String value, int limit) { if (value == null) return ""; if (value.length() <= limit) return value; if (limit <= 3) return value.substring(0, limit); int prefixLength = (limit - 3) / 2; return value.substring(0, prefixLength) + "..." + value.substring(value.length() - (limit - 3 - prefixLength)); }
    public Document get(String documentId) { return documents.findById(documentId).map(this::load).orElseThrow(DocumentNotFoundException::new); }
    public Document update(String documentId, UpdateCommand command) { return transactions.execute(status -> updateInTransaction(documentId, command)); }
    private Document updateInTransaction(String documentId, UpdateCommand command) { Document document = documents.findByIdForUpdate(documentId).orElseThrow(DocumentNotFoundException::new); List<DocumentSection> current = sections.findByDocumentIdOrderByPositionAsc(documentId); validate(command, current); Map<String, DocumentSection> byId = current.stream().collect(Collectors.toMap(DocumentSection::getId, s -> s)); document.updateTitle(command.title()); for (int i = 0; i < current.size(); i++) current.get(i).updateEditableFields(current.get(i).getTitle(), current.get(i).getContent(), -(i + 1), current.get(i).isHidden()); sections.saveAll(current); sections.flush(); List<DocumentSection> reordered = new ArrayList<>(); for (int position = 0; position < command.sections().size(); position++) { SectionUpdate requested = command.sections().get(position); DocumentSection section = byId.get(requested.id()); section.updateEditableFields(requested.title(), requested.content(), position, requested.hidden()); reordered.add(section); } sections.saveAll(reordered); sections.flush(); document.replaceSections(reordered); return document; }
    private void validate(UpdateCommand command, List<DocumentSection> current) { if (command == null || blankOrTooLong(command.title(), TITLE_LIMIT)) throw new InvalidDocumentUpdateException("Document title is required and must be at most 255 characters"); if (command.sections() == null || command.sections().size() != current.size()) throw new InvalidDocumentUpdateException("Sections must include exactly the document's current sections"); Set<String> ids = current.stream().map(DocumentSection::getId).collect(Collectors.toSet()); Set<String> requested = new HashSet<>(); for (SectionUpdate section : command.sections()) { if (section == null || section.id() == null || !requested.add(section.id()) || !ids.contains(section.id())) throw new InvalidDocumentUpdateException("Sections must contain unique IDs belonging to this document"); if (blankOrTooLong(section.title(), TITLE_LIMIT)) throw new InvalidDocumentUpdateException("Section title is required and must be at most 255 characters"); if (blankOrTooLong(section.content(), CONTENT_LIMIT)) throw new InvalidDocumentUpdateException("Section content is required and must be at most 10000 characters"); } }
    private static boolean blankOrTooLong(String value, int limit) { return value == null || value.isBlank() || value.length() > limit; }
    private Document load(Document document) { document.replaceSections(sections.findByDocumentIdOrderByPositionAsc(document.getId())); return document; }
    public record UpdateCommand(String title, List<SectionUpdate> sections) {}
    public record SectionUpdate(String id, String title, String content, boolean hidden) {
        public SectionUpdate(String id, String title, String content) { this(id, title, content, false); }
    }
    public static class AnalysisNotFoundException extends RuntimeException { public AnalysisNotFoundException() { super("Analysis not found"); } }
    public static class ApplicationNotFoundException extends RuntimeException { public ApplicationNotFoundException() { super("Application not found"); } }
    public static class AnalysisNotCompletedException extends RuntimeException { public AnalysisNotCompletedException() { super("Documents can only be generated from completed analyses"); } }
    public static class DraftReplacementConfirmationRequiredException extends RuntimeException { public DraftReplacementConfirmationRequiredException() { super("Replacing an existing draft with a different language or type requires confirmation"); } }
    public static class InvalidGenerationRequestException extends RuntimeException { public InvalidGenerationRequestException(String message) { super(message); } }
    public static class DocumentNotFoundException extends RuntimeException { public DocumentNotFoundException() { super("Document not found"); } }
    public static class InvalidDocumentUpdateException extends RuntimeException { public InvalidDocumentUpdateException(String message) { super(message); } }
}
