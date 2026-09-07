package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentServiceTest {
    @Test
    void rejectsAnalysisThatIsNotCompleted() {
        Analysis analysis = new Analysis("application-1");
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));

        DocumentService service = service(analyses, mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), mock(DocumentRepository.class), mock(DocumentSectionRepository.class));

        assertThatThrownBy(() -> service.generate(analysis.getId()))
                .isInstanceOf(DocumentService.AnalysisNotCompletedException.class);
    }

    @Test
    void generatesSpanishUserManualWithApplicationTitleAndGuidance() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Page page = new Page(analysis.getId(), "http://localhost/admin", "Panel");
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        UIElementRepository elements = mock(UIElementRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.empty());
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(page));
        when(elements.findByPageId(page.getId())).thenReturn(List.of(new UIElement(page.getId(), "button", "#save", "Save", ActionClassification.MUTATING)));
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Document document = service(analyses, pages, elements, mock(ScreenshotRepository.class), documents, sections)
                .generate(analysis.getId(), Document.DocumentLanguage.ES, Document.DocumentType.USER_MANUAL);

        assertThat(document.getTitle()).isEqualTo("Manual de usuario \"FlowPilot\"");
            assertThat(document.getLanguage()).isEqualTo(Document.DocumentLanguage.ES);
        assertThat(document.getType()).isEqualTo(Document.DocumentType.USER_MANUAL);
        assertThat(document.getSections().getFirst().getContent()).contains("Esta pantalla", "Pasos", "No se identificaron acciones").doesNotContain("Save", "#save", "SAFE", "UNKNOWN", "MUTATING", "clasificación", "Referencia técnica");

    }

    @Test
    void generatesStablePageSectionsWithSourceTraceability() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Page zulu = new Page(analysis.getId(), "http://localhost/z", "FlowPilot");
        Page alpha = new Page(analysis.getId(), "http://localhost/admin/users", "FlowPilot");
        UIElement element = new UIElement(alpha.getId(), "button", "#save", "Save", ActionClassification.MUTATING);
            UIElement safeElement = new UIElement(alpha.getId(), "link", "a.help", "Help", ActionClassification.SAFE);
            UIElement unknownElement = new UIElement(alpha.getId(), "input", "#query", "Search", ActionClassification.UNKNOWN);
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        UIElementRepository elements = mock(UIElementRepository.class);
        ScreenshotRepository screenshots = mock(ScreenshotRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.empty());
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(zulu, alpha));
            when(elements.findByPageId(alpha.getId())).thenReturn(List.of(element, safeElement, unknownElement));
        when(elements.findByPageId(zulu.getId())).thenReturn(List.of());
        when(screenshots.findByPageIdOrderByIdAsc(alpha.getId())).thenReturn(List.of(new Screenshot(alpha.getId(), new byte[]{1})));
        when(screenshots.findByPageIdOrderByIdAsc(zulu.getId())).thenReturn(List.of());
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Document document = service(analyses, pages, elements, screenshots, documents, sections).generate(analysis.getId());

        assertThat(document.getTitle()).isEqualTo("User manual \"FlowPilot\"");
            assertThat(document.getApplicationId()).isEqualTo("application-1");
        assertThat(document.getSourceAnalysisId()).isEqualTo(analysis.getId());
        assertThat(document.getSections()).extracting(DocumentSection::getTitle)
                    .containsExactly("Admin: FlowPilot (/admin/users)", "Z: FlowPilot (/z)");
        assertThat(document.getSections().get(0).getSourcePageId()).isEqualTo(alpha.getId());
        assertThat(document.getSections().get(0).getScreenshotId()).isNotNull();
        assertThat(document.getSections().get(0).getContent())
                .contains("This screen", "Steps", "1.", "Help", "Open the link")
                .doesNotContain("Save", "#save", "#query", "SAFE", "UNKNOWN", "MUTATING", "classification", "Technical reference");
    }

    @Test
    void boundsOversizedGeneratedTitleAndContentWithoutLosingClassificationOrTraceability() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Page page = new Page(analysis.getId(), "http://localhost/" + "route".repeat(150), "title".repeat(150));
        UIElement first = new UIElement(page.getId(), "button", "#save", "Save", ActionClassification.MUTATING);
        List<UIElement> manyElements = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) manyElements.add(new UIElement(page.getId(), "link", "#help-" + i, "Help", ActionClassification.SAFE));
        manyElements.add(0, first);
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        UIElementRepository elements = mock(UIElementRepository.class);
        ScreenshotRepository screenshots = mock(ScreenshotRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.empty());
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(page));
        when(elements.findByPageId(page.getId())).thenReturn(manyElements);
        Screenshot screenshot = new Screenshot(page.getId(), new byte[]{1});
        when(screenshots.findByPageIdOrderByIdAsc(page.getId())).thenReturn(List.of(screenshot));
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Document document = service(analyses, pages, elements, screenshots, documents, sections).generate(analysis.getId());
        DocumentSection section = document.getSections().getFirst();

        assertThat(section.getTitle()).hasSizeLessThanOrEqualTo(255);
        assertThat(section.getContent()).hasSizeLessThanOrEqualTo(10000);
        assertThat(section.getContent()).contains("Help").doesNotContain("MUTATING", "#save");
        assertThat(section.getSourcePageId()).isEqualTo(page.getId());
        assertThat(section.getScreenshotId()).isEqualTo(screenshot.getId());
    }

    @Test
    void includesOnlySafeAndApprovedUnknownElementsWithoutTechnicalMetadata() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Page page = new Page(analysis.getId(), "http://localhost/home", "Home");
        UIElement safe = new UIElement(page.getId(), "link", "a.help", "Help", ActionClassification.SAFE);
        UIElement approvedUnknown = new UIElement(page.getId(), "input", "#query", "Search", ActionClassification.UNKNOWN);
        approvedUnknown.setManualInclusionApproved(true);
        UIElement unapprovedUnknown = new UIElement(page.getId(), "button", "#advanced", "Advanced", ActionClassification.UNKNOWN);
        UIElement mutating = new UIElement(page.getId(), "button", "#save", "Save", ActionClassification.MUTATING);
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        UIElementRepository elements = mock(UIElementRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.empty());
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(page));
        when(elements.findByPageId(page.getId())).thenReturn(List.of(safe, approvedUnknown, unapprovedUnknown, mutating));
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Document document = service(analyses, pages, elements, mock(ScreenshotRepository.class), documents, sections).generate(analysis.getId());

        assertThat(document.getSections().getFirst().getContent())
                .contains("Help", "Search")
                .doesNotContain("Save", "Advanced", "a.help", "#query", "SAFE", "UNKNOWN", "MUTATING", "classification", "Technical reference");
    }

    @Test
    void usesNaturalControlSpecificInstructionsInEnglishAndSpanish() {
        String english = generatedManualContent(Document.DocumentLanguage.EN);
        assertThat(english).contains(
                "Open the link \"Help\" to continue.",
                "Select the button \"Continue\" to continue.",
                "Select the button \"Submit\" to continue.",
                "Enter the information in the field \"Email\".",
                "Write the information in the field \"Notes\".",
                "Choose an option in \"Country\".",
                "Choose an option in \"Region\".",
                "Choose an option in \"Role\".",
                "Select the option \"Terms\".",
                "Select the option \"Plan\".",
                "Use the control \"Custom action\" to continue.",
                "Use the control \"Approved custom action\" to continue.",
                "Enter the information in the field \"Approved email\".")
                .doesNotContain("approved procedure");

        String spanish = generatedManualContent(Document.DocumentLanguage.ES);
        assertThat(spanish).contains(
                "Abrí el enlace \"Help\" para continuar.",
                "Seleccioná el botón \"Continue\" para continuar.",
                "Seleccioná el botón \"Submit\" para continuar.",
                "Ingresá la información en el campo \"Email\".",
                "Escribí la información en el campo \"Notes\".",
                "Elegí una opción en \"Country\".",
                "Elegí una opción en \"Region\".",
                "Elegí una opción en \"Role\".",
                "Marcá la opción \"Terms\".",
                "Seleccioná la opción \"Plan\".",
                "Usá el control \"Custom action\" para continuar.",
                "Usá el control \"Approved custom action\" para continuar.",
                "Ingresá la información en el campo \"Approved email\".")
                .doesNotContain("procedimiento aprobado");
    }

    @Test
    void groupsManualInstructionsWithFunctionalContextInEnglishAndSpanish() {
        String english = groupedManualContent(Document.DocumentLanguage.EN);
        assertThat(english).contains(
                "This screen helps you work with \"Catalog\".",
                "Navigation:\n1. Open the link \"Browse catalog\" to continue.",
                "Information:\n2. Enter the information in the field \"Search catalog\".",
                "Actions:\n3. Select the button \"Apply filters\" to continue.\n4. Select the option \"Compact view\".");

        String spanish = groupedManualContent(Document.DocumentLanguage.ES);
        assertThat(spanish).contains(
                "Esta pantalla te ayuda a trabajar con \"Catalog\".",
                "Navegación:\n1. Abrí el enlace \"Browse catalog\" para continuar.",
                "Información:\n2. Ingresá la información en el campo \"Search catalog\".",
                "Acciones:\n3. Seleccioná el botón \"Apply filters\" para continuar.\n4. Seleccioná la opción \"Compact view\".");
    }

    @Test
    void omitsEmptyInstructionGroups() {
        String content = groupedManualContent(Document.DocumentLanguage.EN, List.of(
                new UIElement("page-1", "link", "a.catalog", "Browse catalog", ActionClassification.SAFE)));

        assertThat(content)
                .contains("Navigation:\n1. Open the link \"Browse catalog\" to continue.")
                .doesNotContain("Information:", "Actions:");
    }

    @Test
    void selectsTheStableFirstScreenshotWhenPageHasMultipleScreenshots() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Page page = new Page(analysis.getId(), "http://localhost/home", "Home");
        Screenshot first = mock(Screenshot.class);
        Screenshot second = mock(Screenshot.class);
        when(first.getId()).thenReturn("shot-a");
        when(second.getId()).thenReturn("shot-b");
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        ScreenshotRepository screenshots = mock(ScreenshotRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.empty());
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(page));
        when(screenshots.findByPageIdOrderByIdAsc(page.getId())).thenReturn(List.of(first, second));
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Document document = service(analyses, pages, mock(UIElementRepository.class), screenshots, documents, sections).generate(analysis.getId());

        assertThat(document.getSections().getFirst().getScreenshotId()).isEqualTo("shot-a");
    }

    @Test
    void updatesEditableFieldsWhilePreservingTraceabilityAndOrder() {
        Document document = new Document("analysis-1", "application-1");
        DocumentSection first = new DocumentSection(document.getId(), 0, "page-1", "shot-1", "First", "first content");
        DocumentSection second = new DocumentSection(document.getId(), 1, "page-2", null, "Second", "second content");
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(documents.findByIdForUpdate(document.getId())).thenReturn(Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of(first, second));
        when(documents.save(document)).thenReturn(document);

        Document result = service(mock(AnalysisRepository.class), mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), documents, sections)
                .update(document.getId(), new DocumentService.UpdateCommand("Edited document", List.of(
                        new DocumentService.SectionUpdate(second.getId(), "Reordered", "new content", true),
                        new DocumentService.SectionUpdate(first.getId(), "Updated first", "updated content", false))));

        assertThat(result.getTitle()).isEqualTo("Edited document");
        assertThat(result.getSections()).extracting(DocumentSection::getId).containsExactly(second.getId(), first.getId());
        assertThat(result.getSections().get(0).getSourcePageId()).isEqualTo("page-2");
        assertThat(result.getSections().get(0).getScreenshotId()).isNull();
        assertThat(result.getSections().get(0).getTitle()).isEqualTo("Reordered");
        assertThat(result.getSections().get(0).isHidden()).isTrue();
        assertThat(result.getSections().get(1).isHidden()).isFalse();
        verify(sections, times(2)).flush();
    }

    @Test
    void rejectsInvalidTitleAndSectionSet() {
        Document document = new Document("analysis-1", "application-1");
        DocumentSection section = new DocumentSection(document.getId(), 0, "page-1", null, "Title", "Content");
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(documents.findByIdForUpdate(document.getId())).thenReturn(Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of(section));
        DocumentService service = service(mock(AnalysisRepository.class), mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), documents, sections);

        assertThatThrownBy(() -> service.update(document.getId(), null))
                .isInstanceOf(DocumentService.InvalidDocumentUpdateException.class);
        assertThatThrownBy(() -> service.update(document.getId(), new DocumentService.UpdateCommand("Valid", java.util.Arrays.asList((DocumentService.SectionUpdate) null))))
                .isInstanceOf(DocumentService.InvalidDocumentUpdateException.class);
        assertThatThrownBy(() -> service.update(document.getId(), new DocumentService.UpdateCommand(" ", List.of(new DocumentService.SectionUpdate(section.getId(), "Title", "Content")))))
                .isInstanceOf(DocumentService.InvalidDocumentUpdateException.class);
        assertThatThrownBy(() -> service.update(document.getId(), new DocumentService.UpdateCommand("Valid", List.of())))
                .isInstanceOf(DocumentService.InvalidDocumentUpdateException.class);
        assertThatThrownBy(() -> service.update(document.getId(), new DocumentService.UpdateCommand("Valid", List.of(
                new DocumentService.SectionUpdate(section.getId(), "Title", "x".repeat(10001))))))
                .isInstanceOf(DocumentService.InvalidDocumentUpdateException.class);
        assertThatThrownBy(() -> service.update(document.getId(), new DocumentService.UpdateCommand("Valid", List.of(
                new DocumentService.SectionUpdate(section.getId(), "Title", "Content"),
                new DocumentService.SectionUpdate(section.getId(), "Title", "Content")))))
                .isInstanceOf(DocumentService.InvalidDocumentUpdateException.class);
    }

    @Test
    void rejectsUnknownSectionIds() {
        Document document = new Document("analysis-1", "application-1");
        DocumentSection section = new DocumentSection(document.getId(), 0, "page-1", null, "Title", "Content");
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(documents.findByIdForUpdate(document.getId())).thenReturn(Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of(section));
        DocumentService service = service(mock(AnalysisRepository.class), mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), documents, sections);

        assertThatThrownBy(() -> service.update(document.getId(), new DocumentService.UpdateCommand("Valid", List.of(new DocumentService.SectionUpdate("other", "Title", "Content", false)))))
                .isInstanceOf(DocumentService.InvalidDocumentUpdateException.class);
    }

    @Test
    void recoversFromConcurrentDuplicateAndReturnsCommittedDraft() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Document existing = new Document(analysis.getId(), analysis.getApplicationId());
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId()))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(documents.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        Document result = service(analyses, mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), documents, mock(DocumentSectionRepository.class)).generate(analysis.getId());

        assertThat(result).isSameAs(existing);
    }

    @Test
    void regeneratesLegacyTechnicalUserManualFromCurrentEvidence() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Page page = new Page(analysis.getId(), "http://localhost/home", "Home");
        Document existing = new Document(analysis.getId(), analysis.getApplicationId());
        DocumentSection legacySection = new DocumentSection(existing.getId(), 0, page.getId(), null, "Legacy", "Technical reference: classification SAFE; selector: \"a.help\".");
        UIElement safe = new UIElement(page.getId(), "link", "a.help", "Help", ActionClassification.SAFE);
        UIElement mutating = new UIElement(page.getId(), "button", "#save", "Save", ActionClassification.MUTATING);
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        UIElementRepository elements = mock(UIElementRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.of(existing));
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(page));
        when(elements.findByPageId(page.getId())).thenReturn(List.of(safe, mutating));
        when(sections.findByDocumentIdOrderByPositionAsc(existing.getId())).thenReturn(List.of(legacySection));
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Document result = service(analyses, pages, elements, mock(ScreenshotRepository.class), documents, sections).generate(analysis.getId());

        assertThat(result).isSameAs(existing);
        assertThat(result.getSections().getFirst().getContent())
                .contains("Help")
                .doesNotContain("Technical reference", "classification", "SAFE", "a.help", "#save", "Save", "MUTATING");
        verify(sections).deleteAll(List.of(legacySection));
    }

    @Test
    void reusesCleanExistingDraftForRepeatedGeneration() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Document existing = new Document(analysis.getId(), analysis.getApplicationId());
        DocumentSection cleanSection = new DocumentSection(existing.getId(), 0, "page-1", null, "Home", "This screen helps you work with \"Home\".");
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.of(existing));
        when(sections.findByDocumentIdOrderByPositionAsc(existing.getId())).thenReturn(List.of(cleanSection));

        AnalysisRepository analyses = mock(AnalysisRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        PageRepository pages = mock(PageRepository.class);
        Document result = service(analyses, pages, mock(UIElementRepository.class), mock(ScreenshotRepository.class), documents, sections).generate(analysis.getId());

        assertThat(result).isSameAs(existing);
        assertThat(result.getSections()).containsExactly(cleanSection);
        verify(documents, never()).save(any());
        verify(pages, never()).findByAnalysisId(any());
        verify(sections, never()).deleteAll(any());
    }

    private static String groupedManualContent(Document.DocumentLanguage language) {
        return groupedManualContent(language, List.of(
                new UIElement("page-1", "link", "a.catalog", "Browse catalog", ActionClassification.SAFE),
                new UIElement("page-1", "input", "#search", "Search catalog", ActionClassification.SAFE),
                new UIElement("page-1", "button", "#apply", "Apply filters", ActionClassification.SAFE),
                new UIElement("page-1", "radio", "#compact", "Compact view", ActionClassification.SAFE)));
    }

    private static String groupedManualContent(Document.DocumentLanguage language, List<UIElement> manualElements) {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Page page = new Page(analysis.getId(), "http://localhost/catalog", "Catalog");
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        UIElementRepository elements = mock(UIElementRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.empty());
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(page));
        when(elements.findByPageId(page.getId())).thenReturn(manualElements.stream()
                .map(element -> new UIElement(page.getId(), element.getKind(), element.getSelector(), element.getAccessibleName(), element.getActionClassification()))
                .toList());
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        return service(analyses, pages, elements, mock(ScreenshotRepository.class), documents, sections)
                .generate(analysis.getId(), language, Document.DocumentType.USER_MANUAL)
                .getSections().getFirst().getContent();
    }

    private static String generatedManualContent(Document.DocumentLanguage language) {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Page page = new Page(analysis.getId(), "http://localhost/home", "Home");
        List<UIElement> manualElements = new java.util.ArrayList<>(List.of(
                new UIElement(page.getId(), "link", "a.help", "Help", ActionClassification.SAFE),
                new UIElement(page.getId(), "button", "#continue", "Continue", ActionClassification.SAFE),
                new UIElement(page.getId(), "submit", "#submit", "Submit", ActionClassification.SAFE),
                new UIElement(page.getId(), "input", "#email", "Email", ActionClassification.SAFE),
                new UIElement(page.getId(), "textarea", "#notes", "Notes", ActionClassification.SAFE),
                new UIElement(page.getId(), "select", "#country", "Country", ActionClassification.SAFE),
                new UIElement(page.getId(), "dropdown", "#region", "Region", ActionClassification.SAFE),
                new UIElement(page.getId(), "combobox", "#role", "Role", ActionClassification.SAFE),
                new UIElement(page.getId(), "checkbox", "#terms", "Terms", ActionClassification.SAFE),
                new UIElement(page.getId(), "radio", "#plan", "Plan", ActionClassification.SAFE),
                new UIElement(page.getId(), "custom", "#custom", "Custom action", ActionClassification.SAFE)));
        UIElement approvedUnknown = new UIElement(page.getId(), "custom", "#approved-custom", "Approved custom action", ActionClassification.UNKNOWN);
        approvedUnknown.setManualInclusionApproved(true);
        UIElement approvedUnknownInput = new UIElement(page.getId(), "input", "#approved-email", "Approved email", ActionClassification.UNKNOWN);
        approvedUnknownInput.setManualInclusionApproved(true);
        manualElements.addAll(List.of(approvedUnknown, approvedUnknownInput));
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        UIElementRepository elements = mock(UIElementRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.empty());
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(page));
        when(elements.findByPageId(page.getId())).thenReturn(manualElements);
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        return service(analyses, pages, elements, mock(ScreenshotRepository.class), documents, sections)
                .generate(analysis.getId(), language, Document.DocumentType.USER_MANUAL)
                .getSections().getFirst().getContent();
    }

    private static TargetApplicationRepository testApplications() {
        TargetApplicationRepository applications = mock(TargetApplicationRepository.class);
        when(applications.findById(any())).thenReturn(Optional.of(new TargetApplication("project-1", "FlowPilot", "http://localhost", null, null, null)));
        return applications;
    }

    private static DocumentService service(AnalysisRepository analyses, PageRepository pages, UIElementRepository elements, ScreenshotRepository screenshots, DocumentRepository documents, DocumentSectionRepository sections) {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(mock(TransactionStatus.class));
        return new DocumentService(analyses, pages, elements, screenshots, documents, sections, testApplications(), transactions);
    }
}
