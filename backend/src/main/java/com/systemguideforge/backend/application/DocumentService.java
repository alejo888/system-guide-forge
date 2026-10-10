package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.text.Collator;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
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
    /** Verbs that may permanently remove information: English, and Spanish infinitives and tú/vos/usted imperatives with an
     * optional attached pronoun ("Quitarlo", "Elimínalo", "Borre"). Deactivating or disabling is reversible, so those verbs
     * are deliberately not listed. Names are NFC-normalized before matching so decomposed accents match. */
    private static final Pattern DESTRUCTIVE_VERBS = Pattern.compile(
            "\\b((delete|remove|erase)s?|(elim[ií]n|b[oó]rr|qu[ií]t)(ar|a|á|e)(me|te|se|lo|la|le|nos|los|las|les)?)\\b",
            Pattern.UNICODE_CHARACTER_CLASS);
    /** Option lists longer than this are sorted by name so options sharing a prefix (e.g. a role) read together. */
    private static final int OPTION_SORT_THRESHOLD = 10;

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
        List<Page> derivedPages = moduleDeriver.derive(sourcePages).stream().flatMap(module -> module.pages().stream()).map(modulePage -> pagesById.get(modulePage.id())).toList();
        Map<String, String> labels = moduleDeriver.navigationLabels(sourcePages, sourcePages.stream().flatMap(page -> elements.findByPageId(page.getId()).stream()).toList());
        List<List<Page>> routeGroups = groupByRouteTemplate(derivedPages);
        for (int position = 0; position < routeGroups.size(); position++) {
            List<Page> routeGroup = routeGroups.get(position);
            Page page = routeGroup.get(0);
            List<UIElement> pageElements = sectionElements(routeGroup);
            String name = sectionName(routeGroup, language);
            String content = describePage(page, name, pageElements, language, routeGroup.size());
            String title = boundedText(descriptiveTitle(page, name, language, labels), TITLE_LIMIT);
            String screenshotId = screenshots.findByPageIdOrderByIdAsc(page.getId()).stream().findFirst().map(Screenshot::getId).orElse(null);
            DocumentSection section = sections.save(new DocumentSection(document.getId(), position, page.getId(), screenshotId, title, content));
            document.addSection(section);
        }
        return document;
    }

    /** Pages of the manual order that share a route template collapse into one group led by the first page; login pages never collapse. */
    private List<List<Page>> groupByRouteTemplate(List<Page> orderedPages) {
        Map<String, List<Page>> groups = new LinkedHashMap<>();
        for (Page page : orderedPages) {
            String template = moduleDeriver.routeTemplateFor(page.getUrl());
            boolean hasIdSegment = !template.equals(moduleDeriver.routeFor(page.getUrl()));
            // Only pages that differ by an id segment collapse; query- or hash-only differences keep their own section.
            String key = page.getKind() == PageKind.LOGIN || !hasIdSegment ? "page:" + page.getId() : moduleDeriver.moduleKeyFor(page.getUrl()) + "|" + template;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(page);
        }
        return new ArrayList<>(groups.values());
    }

    /** The example page's elements plus approved UNKNOWN elements from the other collapsed pages, so no manual approval is lost. */
    private List<UIElement> sectionElements(List<Page> routeGroup) {
        Comparator<UIElement> order = Comparator.comparing(UIElement::getKind, Comparator.nullsFirst(String::compareTo)).thenComparing(UIElement::getSelector, Comparator.nullsFirst(String::compareTo)).thenComparing(UIElement::getId);
        List<UIElement> result = new ArrayList<>(elements.findByPageId(routeGroup.get(0).getId()).stream().sorted(order).toList());
        // Only elements the manual will actually show count as duplicates; excluded ones must not hide an approval.
        Set<String> seen = new HashSet<>(result.stream().filter(this::isIncludedInManual).map(this::elementIdentity).toList());
        routeGroup.stream().skip(1).flatMap(page -> elements.findByPageId(page.getId()).stream().sorted(order))
                .filter(element -> classification(element) == ActionClassification.UNKNOWN && element.isManualInclusionApproved())
                .filter(element -> seen.add(elementIdentity(element)))
                .forEach(result::add);
        return result;
    }
    private String elementIdentity(UIElement element) {
        boolean named = element.getAccessibleName() != null && !element.getAccessibleName().isBlank();
        return element.getKind() + "|" + (named ? "name:" + element.getAccessibleName() : "selector:" + element.getSelector());
    }
    private String descriptiveTitle(Page page, String name, Document.DocumentLanguage language, Map<String, String> labels) {
        if (page.getKind() == PageKind.LOGIN) return language == Document.DocumentLanguage.ES ? "Cómo ingresar al sistema" : "How to sign in";
        return moduleDeriver.moduleNameFor(page.getUrl(), labels, language) + ": " + name + " (" + moduleDeriver.routeTemplateFor(page.getUrl()) + ")";
    }
    /** Collapsed pages whose names differ show record data (e.g. each project's name), so the section uses a generic name instead. */
    private String sectionName(List<Page> routeGroup, Document.DocumentLanguage language) {
        String exampleName = pageTitle(routeGroup.get(0), language);
        boolean recordSpecific = routeGroup.stream().map(page -> pageTitle(page, language)).anyMatch(name -> !name.equals(exampleName));
        if (!recordSpecific) return exampleName;
        return language == Document.DocumentLanguage.ES ? "Detalle del elemento" : "Item details";
    }
    /** The visible heading when present, else the raw title, else a generic label. */
    private String pageTitle(Page page, Document.DocumentLanguage language) {
        if (page.getHeading() != null && !page.getHeading().isBlank()) return page.getHeading().trim();
        if (page.getTitle() != null && !page.getTitle().isBlank()) return page.getTitle().trim();
        return language == Document.DocumentLanguage.ES ? "Página sin título" : "Untitled page";
    }
    private String documentTitle(Document.DocumentType type, String applicationName, Document.DocumentLanguage language) { return (type == Document.DocumentType.USER_MANUAL ? (language == Document.DocumentLanguage.ES ? "Manual de usuario" : "User manual") : type.name()) + " \"" + boundedText(applicationName, 200) + "\""; }

    private String describePage(Page page, String name, List<UIElement> pageElements, Document.DocumentLanguage language, int routePageCount) {
        boolean spanish = language == Document.DocumentLanguage.ES;
        StringBuilder result = new StringBuilder();
        result.append(pageIntroduction(page, name, language, routePageCount)).append("\n\n");
        if (page.getKind() == PageKind.LOGIN) {
            result.append(spanish ? "Pasos:\n" : "Steps:\n");
            appendLoginSteps(page, spanish, result);
            return boundedText(result.toString(), CONTENT_LIMIT);
        }
        int step = 1;
        // Repeated controls (a logo in header and sidebar, the same link on every card) would produce identical instructions.
        Set<String> listed = new HashSet<>();
        List<UIElement> manualElements = sortLongOptionLists(pageElements.stream().filter(this::isIncludedInManual).filter(element -> listed.add(elementIdentity(element))).toList(), spanish);
        for (InstructionGroup group : InstructionGroup.values()) {
            List<UIElement> groupElements = manualElements.stream()
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
    /** Fixed sign-in steps built only from the real captured control names; unrelated controls (logo, register,
     * forgot password, show password) are never part of the login section. */
    private void appendLoginSteps(Page page, boolean spanish, StringBuilder result) {
        result.append("1. ").append(loginUsernameInstruction(page.getLoginUsernameLabel(), spanish)).append('\n');
        result.append("2. ").append(loginPasswordInstruction(page.getLoginPasswordLabel(), spanish)).append('\n');
        result.append("3. ").append(loginSubmitInstruction(page.getLoginSubmitLabel(), spanish)).append('\n');
    }
    private String loginUsernameInstruction(String label, boolean spanish) {
        String quoted = quotedLoginLabel(label);
        if (quoted == null) return spanish ? "Ingresá tu usuario o correo electrónico." : "Enter your username or email.";
        return spanish ? "Ingresá tu usuario o correo electrónico en el campo " + quoted + "." : "Enter your username or email in the " + quoted + " field.";
    }
    private String loginPasswordInstruction(String label, boolean spanish) {
        String quoted = quotedLoginLabel(label);
        if (quoted == null) return spanish ? "Ingresá tu contraseña." : "Enter your password.";
        return spanish ? "Ingresá tu contraseña en el campo " + quoted + "." : "Enter your password in the " + quoted + " field.";
    }
    private String loginSubmitInstruction(String label, boolean spanish) {
        String quoted = quotedLoginLabel(label);
        if (quoted == null) return spanish ? "Presioná el botón de inicio de sesión." : "Press the sign-in button.";
        return spanish ? "Presioná " + quoted + "." : "Press " + quoted + ".";
    }
    /** Null when the label is missing or was redacted (e.g. "[redacted]"), so generic wording is used instead of leaking it. */
    private String quotedLoginLabel(String label) {
        if (label == null) return null;
        String trimmed = boundedText(label.trim(), 200);
        if (trimmed.isEmpty() || trimmed.contains("[redacted]")) return null;
        return "«" + trimmed + "»";
    }
    private String pageIntroduction(Page page, String name, Document.DocumentLanguage language, int routePageCount) {
        if (page.getKind() == PageKind.LOGIN) {
            return language == Document.DocumentLanguage.ES
                    ? "Esta pantalla te permite ingresar al sistema. Ingresá tu usuario o correo electrónico y tu contraseña en el formulario y luego presioná el botón de inicio de sesión."
                    : "This screen lets you sign in to the application. Enter your username or email and your password in the form, then press the sign-in button.";
        }
        String title = boundedText(name, 3000);
        String route = boundedText(moduleDeriver.routeTemplateFor(page.getUrl()), 3000);
        String example = boundedText(moduleDeriver.routeFor(page.getUrl()), 3000);
        boolean spanish = language == Document.DocumentLanguage.ES;
        String introduction = spanish
                ? "La ruta " + route + " muestra la página \"" + title + "\"."
                : "The " + route + " route displays the \"" + title + "\" page.";
        if (routePageCount < 2) return introduction;
        return introduction + (spanish
                ? " Esta pantalla es la misma para cada elemento; el ejemplo corresponde a " + example + "."
                : " This screen is the same for every item; the example shown is " + example + ".");
    }
    /** More than {@link #OPTION_SORT_THRESHOLD} checkbox options are re-ordered by name (stable, locale-aware) inside the
     * slots they already occupy, so other controls keep their place; shorter lists keep the captured order. Radio buttons
     * are never re-ordered because their order and adjacency identify the question each choice answers. */
    private List<UIElement> sortLongOptionLists(List<UIElement> manualElements, boolean spanish) {
        List<UIElement> options = manualElements.stream().filter(this::isCheckboxOption).toList();
        if (options.size() <= OPTION_SORT_THRESHOLD) return manualElements;
        Collator collator = Collator.getInstance(spanish ? Locale.forLanguageTag("es") : Locale.ENGLISH);
        Iterator<UIElement> sorted = options.stream().sorted(Comparator.comparing((UIElement option) -> displayName(option, spanish), collator)).iterator();
        return manualElements.stream().map(element -> isCheckboxOption(element) ? sorted.next() : element).toList();
    }
    private boolean isCheckboxOption(UIElement element) {
        return switch (manualKind(element)) {
            case "checkbox", "input-checkbox" -> true;
            default -> false;
        };
    }
    private InstructionGroup instructionGroupFor(UIElement element) {
        // MUTATING links are worded as links but remain actions, not navigation.
        if (classification(element) == ActionClassification.MUTATING) return InstructionGroup.ACTIONS;
        return switch (manualKind(element)) {
            case "link", "tab" -> InstructionGroup.NAVIGATION;
            case "input", "textarea", "select", "dropdown", "combobox", "input-checkbox", "input-radio" -> InstructionGroup.INFORMATION;
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
        String instruction = switch (manualKind(element)) {
            case "link" -> classification(element) == ActionClassification.MUTATING
                    ? (spanish ? "Seleccioná el enlace " + name + "." : "Select the " + name + " link.")
                    : (spanish ? "Abrí el enlace " + name + "." : "Open the " + name + " link.");
            case "tab" -> spanish ? "Seleccioná la pestaña " + name + "." : "Select the " + name + " tab.";
            case "button", "submit" -> spanish ? "Presioná el botón " + name + "." : "Press the " + name + " button.";
            case "input" -> spanish ? "Ingresá la información en el campo " + name + "." : "Enter the information in the field " + name + ".";
            case "textarea" -> spanish ? "Escribí la información en el campo " + name + "." : "Write the information in the field " + name + ".";
            case "select", "dropdown", "combobox" -> spanish ? "Elegí una opción en " + name + "." : "Choose an option in " + name + ".";
            case "checkbox" -> spanish ? "Marcá la opción " + name + "." : "Select the option " + name + ".";
            case "radio" -> spanish ? "Seleccioná la opción " + name + "." : "Select the option " + name + ".";
            case "input-checkbox" -> spanish ? "Marcá o desmarcá la opción " + name + "." : "Select or clear the option " + name + ".";
            case "input-radio" -> spanish ? "Elegí la opción " + name + "." : "Choose the option " + name + ".";
            default -> spanish ? "Usá " + name + "." : "Use " + name + ".";
        };
        return step + ". " + instruction + destructiveCaution(element, spanish);
    }
    /** Extra caution for MUTATING actions whose name contains a destructive verb; describing the action never executes it. */
    private String destructiveCaution(UIElement element, boolean spanish) {
        if (classification(element) != ActionClassification.MUTATING || element.getAccessibleName() == null) return "";
        if (!DESTRUCTIVE_VERBS.matcher(Normalizer.normalize(element.getAccessibleName(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT)).find()) return "";
        return spanish ? " Atención: esta acción puede eliminar información de forma permanente." : " Caution: this action may permanently remove information.";
    }
    /** SAFE and approved UNKNOWN controls, plus named form fields and named MUTATING actions: describing a control never executes it. */
    private boolean isIncludedInManual(UIElement element) {
        ActionClassification classification = classification(element);
        if (classification == ActionClassification.SAFE || (classification == ActionClassification.UNKNOWN && element.isManualInclusionApproved())) return true;
        boolean describedWithoutApproval = classification == ActionClassification.MUTATING || (classification == ActionClassification.UNKNOWN && isFormField(element));
        return describedWithoutApproval && hasPrintableName(element);
    }
    /** Input types a person fills in or ticks; buttons, files, hidden inputs and pickers such as color or range are not described without approval. */
    private static final Set<String> FIELD_INPUT_TYPES = Set.of("text", "password", "email", "number", "search", "tel", "url",
            "date", "datetime-local", "month", "week", "time", "checkbox", "radio");
    /** Textareas and field-like inputs; legacy inputs without a captured type keep being treated as fields. */
    private boolean isFormField(UIElement element) {
        String kind = normalizedKind(element.getKind());
        if (kind.equals("textarea")) return true;
        return kind.equals("input") && (element.getControlType() == null || FIELD_INPUT_TYPES.contains(element.getControlType()));
    }
    /** Automatically described controls need a real, unredacted name; "this control" or "[redacted]" would only add noise. */
    private boolean hasPrintableName(UIElement element) { String name = element.getAccessibleName(); return name != null && !name.isBlank() && !name.contains("[redacted]"); }
    /** MUTATING controls (including submit inputs) are presented as actions to press, except links, which keep the link
     * wording; captured checkbox and radio inputs are options to choose, and tab buttons are view switches. Other input types and legacy rows without a
     * captured type keep the text-entry wording. */
    private String manualKind(UIElement element) {
        String kind = normalizedKind(element.getKind());
        if (classification(element) == ActionClassification.MUTATING) return kind.equals("link") ? "link" : "button";
        if (kind.equals("button") && "tab".equals(element.getControlType())) return "tab";
        if (kind.equals("input") && ("checkbox".equals(element.getControlType()) || "radio".equals(element.getControlType()))) return "input-" + element.getControlType();
        return kind;
    }
    private enum InstructionGroup { NAVIGATION, INFORMATION, ACTIONS }
    private boolean isLegacyTechnicalUserManual(Document document) {
        if (document.getType() != Document.DocumentType.USER_MANUAL) return false;
        return sections.findByDocumentIdOrderByPositionAsc(document.getId()).stream()
                .map(DocumentSection::getContent)
                .anyMatch(content -> content != null && (content.contains("Technical reference: classification") || content.contains("Referencia técnica: clasificación")));
    }
    /** Captured anchors are stored with their tag name "a"; they are links for the manual. */
    private String normalizedKind(String kind) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("a") ? "link" : normalized;
    }
    private String displayName(UIElement element, boolean spanish) { return element.getAccessibleName() != null && !element.getAccessibleName().isBlank() ? element.getAccessibleName() : (spanish ? "este control" : "this control"); }
    private ActionClassification classification(UIElement element) { return element.getActionClassification() == null ? ActionClassification.UNKNOWN : element.getActionClassification(); }
    private static String boundedText(String value, int limit) { if (value == null) return ""; if (value.length() <= limit) return value; if (limit <= 3) return value.substring(0, limit); int prefixLength = (limit - 3) / 2; return value.substring(0, prefixLength) + "..." + value.substring(value.length() - (limit - 3 - prefixLength)); }
    public Document get(String documentId) { return documents.findById(documentId).map(this::load).orElseThrow(DocumentNotFoundException::new); }
    public Document getForAnalysis(String analysisId) {
        analyses.findById(analysisId).orElseThrow(AnalysisNotFoundException::new);
        return documents.findBySourceAnalysisId(analysisId).map(this::load).orElseThrow(DocumentNotFoundException::new);
    }
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
