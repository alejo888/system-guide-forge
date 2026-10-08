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
    void requiresExplicitConfirmationBeforeReplacingADraftWithDifferentGenerationSettings() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Document existing = new Document(analysis.getId(), analysis.getApplicationId());
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.of(existing));

        DocumentService service = service(analyses, mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), documents, sections);

        assertThatThrownBy(() -> service.generate(analysis.getId(), Document.DocumentLanguage.ES, Document.DocumentType.USER_MANUAL))
                .isInstanceOf(DocumentService.DraftReplacementConfirmationRequiredException.class);
        verify(sections, never()).deleteAll(any());
    }

    @Test
    void replacesADraftWithDifferentGenerationSettingsAfterExplicitConfirmation() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Document existing = new Document(analysis.getId(), analysis.getApplicationId());
        Page page = new Page(analysis.getId(), "http://localhost/home", "Home");
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.of(existing));
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(page));
        when(sections.findByDocumentIdOrderByPositionAsc(existing.getId())).thenReturn(List.of());
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Document result = service(analyses, pages, mock(UIElementRepository.class), mock(ScreenshotRepository.class), documents, sections)
                .generate(analysis.getId(), Document.DocumentLanguage.ES, Document.DocumentType.USER_MANUAL, true);

        assertThat(result).isSameAs(existing);
        assertThat(result.getLanguage()).isEqualTo(Document.DocumentLanguage.ES);
        verify(sections).deleteAll(List.of());
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
        when(elements.findByPageId(page.getId())).thenReturn(List.of(new UIElement(page.getId(), "button", "#save", "Save", ActionClassification.UNKNOWN)));
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Document document = service(analyses, pages, elements, mock(ScreenshotRepository.class), documents, sections)
                .generate(analysis.getId(), Document.DocumentLanguage.ES, Document.DocumentType.USER_MANUAL);

        assertThat(document.getTitle()).isEqualTo("Manual de usuario \"FlowPilot\"");
            assertThat(document.getLanguage()).isEqualTo(Document.DocumentLanguage.ES);
        assertThat(document.getType()).isEqualTo(Document.DocumentType.USER_MANUAL);
        assertThat(document.getSections().getFirst().getContent()).contains("La ruta /admin muestra la página \"Panel\".", "No se identificaron acciones").doesNotContain("Pasos", "Save", "#save", "SAFE", "UNKNOWN", "MUTATING", "clasificación", "Referencia técnica");

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
                .contains("The /admin/users route displays the \"FlowPilot\" page.", "1.", "Help", "Open the \"Help\" link.",
                        "Enter the information in the field \"Search\".", "Press the \"Save\" button.")
                .doesNotContain("Steps", "#save", "#query", "SAFE", "UNKNOWN", "MUTATING", "classification", "Technical reference");
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
    void includesSafeApprovedUnknownAndDescribedFormControlsWithoutTechnicalMetadata() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Page page = new Page(analysis.getId(), "http://localhost/home", "Home");
        UIElement safe = new UIElement(page.getId(), "link", "a.help", "Help", ActionClassification.SAFE);
        UIElement approvedUnknown = new UIElement(page.getId(), "button", "#query", "Search", ActionClassification.UNKNOWN);
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
                .contains("Help", "Search", "Press the \"Save\" button.")
                .doesNotContain("Advanced", "a.help", "#query", "#save", "SAFE", "UNKNOWN", "MUTATING", "classification", "Technical reference");
    }

    @Test
    void describesNamedFormFieldsAndMutatingActionsWithoutApprovalInSpanish() {
        String content = groupedManualContent(Document.DocumentLanguage.ES, List.of(
                new UIElement("page-1", "input", "#name", "Nombre", ActionClassification.UNKNOWN),
                new UIElement("page-1", "textarea", "#description", "Descripción", ActionClassification.UNKNOWN),
                new UIElement("page-1", "button", "#save", "Guardar", ActionClassification.MUTATING),
                // A submit input is classified MUTATING and is described as an action, not as a field.
                new UIElement("page-1", "input", "#create", "Crear sprint", ActionClassification.MUTATING)));

        assertThat(content)
                .contains("Información:\n1. Ingresá la información en el campo \"Nombre\".\n2. Escribí la información en el campo \"Descripción\".\n"
                        + "Acciones:\n3. Presioná el botón \"Guardar\".\n4. Presioná el botón \"Crear sprint\".")
                .doesNotContain("No se identificaron acciones", "#name", "#save", "UNKNOWN", "MUTATING");
    }

    @Test
    void keepsRequiringApprovalForUnknownControlsThatAreNotFormFields() {
        String content = groupedManualContent(Document.DocumentLanguage.ES, List.of(
                new UIElement("page-1", "button", "#menu", "Abrir menú", ActionClassification.UNKNOWN),
                new UIElement("page-1", "link", "a.more", "Ver más", ActionClassification.UNKNOWN),
                new UIElement("page-1", "select", "#country", "País", ActionClassification.UNKNOWN)));

        assertThat(content)
                .contains("No se identificaron acciones")
                .doesNotContain("Abrir menú", "Ver más", "País");
    }

    @Test
    void skipsAutomaticallyDescribedControlsWithoutAPrintableName() {
        String content = groupedManualContent(Document.DocumentLanguage.EN, List.of(
                new UIElement("page-1", "link", "a.help", "Help", ActionClassification.SAFE),
                new UIElement("page-1", "input", "#unnamed", null, ActionClassification.UNKNOWN),
                new UIElement("page-1", "textarea", "#blank", "  ", ActionClassification.UNKNOWN),
                new UIElement("page-1", "button", "#icon", "", ActionClassification.MUTATING),
                new UIElement("page-1", "input", "#secret", "[redacted]", ActionClassification.UNKNOWN),
                new UIElement("page-1", "button", "#wipe", "Delete [redacted]", ActionClassification.MUTATING)));

        assertThat(content)
                .contains("Navigation:\n1. Open the \"Help\" link.")
                .doesNotContain("this control", "[redacted]", "Information:", "Actions:", "2.");
    }

    @Test
    void describesNamedFormFieldsAndMutatingActionsWithoutApprovalInEnglish() {
        String content = groupedManualContent(Document.DocumentLanguage.EN, List.of(
                new UIElement("page-1", "input", "#name", "Name", ActionClassification.UNKNOWN),
                new UIElement("page-1", "textarea", "#notes", "Notes", ActionClassification.UNKNOWN),
                new UIElement("page-1", "button", "#save", "Save", ActionClassification.MUTATING)));

        assertThat(content).contains("Information:\n1. Enter the information in the field \"Name\".\n2. Write the information in the field \"Notes\".\n"
                + "Actions:\n3. Press the \"Save\" button.");
    }

    @Test
    void presentsTheLoginPageFirstWithFixedSignInStepsFromTheCapturedControlNames() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Page login = new Page(analysis.getId(), "http://localhost/zzz-login", "Sign in", PageKind.LOGIN, "Email", "[redacted]", "Ingresar");
        Page home = new Page(analysis.getId(), "http://localhost/aaa-home", "Home");
        // Unrelated controls (logo link, register, forgot password, show password) must never appear in the login section.
        UIElement logo = new UIElement(login.getId(), "a", "#logo", "FPFlowPilot", ActionClassification.SAFE);
        UIElement register = new UIElement(login.getId(), "a", "#register", "Registrate gratis", ActionClassification.SAFE);
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        UIElementRepository elements = mock(UIElementRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.empty());
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(home, login));
        when(elements.findByPageId(login.getId())).thenReturn(List.of(logo, register));
        when(elements.findByPageId(home.getId())).thenReturn(List.of());
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Document english = service(analyses, pages, elements, mock(ScreenshotRepository.class), documents, sections)
                .generate(analysis.getId(), Document.DocumentLanguage.EN);

        assertThat(english.getSections().getFirst().getSourcePageId()).isEqualTo(login.getId());
        assertThat(english.getSections().getFirst().getTitle()).isEqualTo("How to sign in");
        assertThat(english.getSections().getFirst().getContent())
                .contains("Steps:\n1. Enter your username or email in the «Email» field.")
                // The password label "[redacted]" (safe() already redacted it) falls back to generic wording.
                .contains("Enter your password.")
                .contains("Press «Ingresar».")
                .doesNotContain("[redacted]", "FPFlowPilot", "Registrate gratis");

        Document spanish = service(analyses, pages, elements, mock(ScreenshotRepository.class), documents, sections)
                .generate(analysis.getId(), Document.DocumentLanguage.ES, Document.DocumentType.USER_MANUAL, true);

        assertThat(spanish.getSections().getFirst().getTitle()).isEqualTo("Cómo ingresar al sistema");
        assertThat(spanish.getSections().getFirst().getContent())
                .contains("Pasos:\n1. Ingresá tu usuario o correo electrónico en el campo «Email».")
                .contains("Ingresá tu contraseña.")
                .contains("Presioná «Ingresar».")
                .doesNotContain("[redacted]", "FPFlowPilot", "Registrate gratis");
    }

    @Test
    void usesGenericLoginWordingWhenNoRoleLabelWasCaptured() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Page login = new Page(analysis.getId(), "http://localhost/login", "Sign in", PageKind.LOGIN);
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        UIElementRepository elements = mock(UIElementRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.empty());
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(login));
        when(elements.findByPageId(login.getId())).thenReturn(List.of());
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Document english = service(analyses, pages, elements, mock(ScreenshotRepository.class), documents, sections)
                .generate(analysis.getId(), Document.DocumentLanguage.EN);

        assertThat(english.getSections().getFirst().getContent())
                .contains("Enter your username or email.", "Enter your password.", "Press the sign-in button.")
                .doesNotContain("«", "[redacted]");
    }

    @Test
    void usesNaturalControlSpecificInstructionsInEnglishAndSpanish() {
        String english = generatedManualContent(Document.DocumentLanguage.EN);
        assertThat(english).contains(
                "Open the \"Help\" link.",
                "Press the \"Continue\" button.",
                "Press the \"Submit\" button.",
                "Enter the information in the field \"Email\".",
                "Write the information in the field \"Notes\".",
                "Choose an option in \"Country\".",
                "Choose an option in \"Region\".",
                "Choose an option in \"Role\".",
                "Select the option \"Terms\".",
                "Select the option \"Plan\".",
                "Use \"Custom action\".",
                "Use \"Approved custom action\".",
                "Enter the information in the field \"Approved email\".")
                .doesNotContain("approved procedure");

        String spanish = generatedManualContent(Document.DocumentLanguage.ES);
        assertThat(spanish).contains(
                "Abrí el enlace \"Help\".",
                "Presioná el botón \"Continue\".",
                "Presioná el botón \"Submit\".",
                "Ingresá la información en el campo \"Email\".",
                "Escribí la información en el campo \"Notes\".",
                "Elegí una opción en \"Country\".",
                "Elegí una opción en \"Region\".",
                "Elegí una opción en \"Role\".",
                "Marcá la opción \"Terms\".",
                "Seleccioná la opción \"Plan\".",
                "Usá \"Custom action\".",
                "Usá \"Approved custom action\".",
                "Ingresá la información en el campo \"Approved email\".")
                .doesNotContain("procedimiento aprobado");
    }

    @Test
    void groupsManualInstructionsWithFunctionalContextInEnglishAndSpanish() {
        String english = groupedManualContent(Document.DocumentLanguage.EN);
        assertThat(english).contains(
                "The /catalog route displays the \"Catalog\" page.",
                "Navigation:\n1. Open the \"Browse catalog\" link.",
                "Information:\n2. Enter the information in the field \"Search catalog\".",
                "Actions:\n3. Press the \"Apply filters\" button.\n4. Select the option \"Compact view\".");

        String spanish = groupedManualContent(Document.DocumentLanguage.ES);
        assertThat(spanish).contains(
                "La ruta /catalog muestra la página \"Catalog\".",
                "Navegación:\n1. Abrí el enlace \"Browse catalog\".",
                "Información:\n2. Ingresá la información en el campo \"Search catalog\".",
                "Acciones:\n3. Presioná el botón \"Apply filters\".\n4. Seleccioná la opción \"Compact view\".");
    }

    @Test
    void listsEachNamedControlOnceAndTreatsCapturedAnchorsAsNavigationLinks() {
        Page projects = new Page("a", "http://localhost/projects", "Proyectos");
        Document document = generateForPages(Document.DocumentLanguage.ES, List.of(projects), page -> List.of(
                new UIElement(page.getId(), "a", "a:nth-of-type(1)", "FlowPilot", ActionClassification.SAFE, "/"),
                new UIElement(page.getId(), "a", "a:nth-of-type(2)", "FlowPilot", ActionClassification.SAFE, "/"),
                new UIElement(page.getId(), "a", "a:nth-of-type(3)", "Tablero", ActionClassification.SAFE, "/projects/1/board"),
                new UIElement(page.getId(), "a", "a:nth-of-type(4)", "Tablero", ActionClassification.SAFE, "/projects/3/board"),
                new UIElement(page.getId(), "button", "button:nth-of-type(1)", "Filtrar", ActionClassification.SAFE),
                new UIElement(page.getId(), "button", "button:nth-of-type(2)", "Filtrar", ActionClassification.SAFE)));

        assertThat(document.getSections().get(0).getContent()).contains(
                "Navegación:\n1. Abrí el enlace \"FlowPilot\".\n2. Abrí el enlace \"Tablero\".\n",
                "Acciones:\n3. Presioná el botón \"Filtrar\".\n").doesNotContain("4. ");
    }

    @Test
    void keepsUnnamedControlsDistinctBySelector() {
        Page form = new Page("a", "http://localhost/form", "Form");
        Document document = generateForPages(Document.DocumentLanguage.EN, List.of(form), page -> List.of(
                new UIElement(page.getId(), "input", "input:nth-of-type(1)", "", ActionClassification.SAFE),
                new UIElement(page.getId(), "input", "input:nth-of-type(2)", "", ActionClassification.SAFE)));

        assertThat(document.getSections().get(0).getContent()).contains(
                "1. Enter the information in the field \"this control\".\n2. Enter the information in the field \"this control\".");
    }

    @Test
    void usesEachPageRouteAndTitleToCreateDistinctEvidenceBasedIntroductions() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Page catalog = new Page(analysis.getId(), "http://localhost/catalog", "FlowPilot");
        Page reports = new Page(analysis.getId(), "http://localhost/reports", "FlowPilot");
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        UIElementRepository elements = mock(UIElementRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.empty());
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(catalog, reports));
        when(elements.findByPageId(any())).thenReturn(List.of());
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Document document = service(analyses, pages, elements, mock(ScreenshotRepository.class), documents, sections).generate(analysis.getId());

        assertThat(document.getSections().get(0).getContent()).startsWith("The /catalog route displays the \"FlowPilot\" page.");
            assertThat(document.getSections().get(1).getContent()).startsWith("The /reports route displays the \"FlowPilot\" page.");
            assertThat(document.getSections()).extracting(DocumentSection::getContent)
                .allSatisfy(content -> assertThat(content).contains("route displays"))
                .noneMatch(content -> content.contains("This screen helps you work with"));
    }

    @Test
    void prefersThePageHeadingOverTheRawTitleAndFallsBackToTitleThenUntitled() {
        Page withHeading = new Page("a", "http://localhost/alpha", "FlowPilot", "Team dashboard", null);
        Page blankHeading = new Page("a", "http://localhost/beta", "FlowPilot", "  ", null);
        Page neither = new Page("a", "http://localhost/gamma", " ", null, null);

        Document english = generateForPages(Document.DocumentLanguage.EN, withHeading, blankHeading, neither);
        assertThat(english.getSections()).extracting(DocumentSection::getTitle).containsExactly(
                "Alpha: Team dashboard (/alpha)", "Beta: FlowPilot (/beta)", "Gamma: Untitled page (/gamma)");
        assertThat(english.getSections().get(0).getContent()).startsWith("The /alpha route displays the \"Team dashboard\" page.");

        Document spanish = generateForPages(Document.DocumentLanguage.ES, withHeading, neither);
        assertThat(spanish.getSections().get(0).getContent()).startsWith("La ruta /alpha muestra la página \"Team dashboard\".");
        assertThat(spanish.getSections().get(1).getTitle()).isEqualTo("Gamma: Página sin título (/gamma)");
    }

    @Test
    void usesAGenericNameWhenCollapsedPagesShowRecordSpecificHeadings() {
        Page mobile = new Page("a", "http://localhost/projects/1", "FlowPilot", "FlowPilot Mobile App", null);
        Page migration = new Page("a", "http://localhost/projects/3", "FlowPilot", "Internal API Migration", null);

        Document english = generateForPages(Document.DocumentLanguage.EN, migration, mobile);
        assertThat(english.getSections()).extracting(DocumentSection::getTitle).containsExactly("Projects: Item details (/projects/{id})");
        assertThat(english.getSections().get(0).getContent())
                .startsWith("The /projects/{id} route displays the \"Item details\" page. This screen is the same for every item; the example shown is /projects/1.")
                .doesNotContain("FlowPilot Mobile App");

        Document spanish = generateForPages(Document.DocumentLanguage.ES, migration, mobile);
        assertThat(spanish.getSections()).extracting(DocumentSection::getTitle).containsExactly("Projects: Detalle del elemento (/projects/{id})");
        assertThat(spanish.getSections().get(0).getContent())
                .startsWith("La ruta /projects/{id} muestra la página \"Detalle del elemento\". Esta pantalla es la misma para cada elemento; el ejemplo corresponde a /projects/1.");
    }

    @Test
    void collapsesPagesThatDifferOnlyByIdIntoOneSectionUsingTheFirstPageAsExample() {
        Page board3 = new Page("a", "http://localhost/projects/3/board", "FlowPilot", "Board", null);
        Page board1 = new Page("a", "http://localhost/projects/1/board", "FlowPilot", "Board", null);
        Page create = new Page("a", "http://localhost/projects/new", "FlowPilot", "New project", null);

        Document english = generateForPages(Document.DocumentLanguage.EN, board3, board1, create);

        assertThat(english.getSections()).extracting(DocumentSection::getTitle)
                .containsExactly("Projects: Board (/projects/{id}/board)", "Projects: New project (/projects/new)");
        assertThat(english.getSections()).extracting(DocumentSection::getPosition).containsExactly(0, 1);
        assertThat(english.getSections().get(0).getSourcePageId()).isEqualTo(board1.getId());
        assertThat(english.getSections().get(0).getContent()).startsWith(
                "The /projects/{id}/board route displays the \"Board\" page. This screen is the same for every item; the example shown is /projects/1/board.");
        assertThat(english.getSections().get(1).getContent())
                .startsWith("The /projects/new route displays the \"New project\" page.")
                .doesNotContain("same for every item");

        Document spanish = generateForPages(Document.DocumentLanguage.ES, board3, board1);
        assertThat(spanish.getSections()).hasSize(1);
        assertThat(spanish.getSections().get(0).getContent()).startsWith(
                "La ruta /projects/{id}/board muestra la página \"Board\". Esta pantalla es la misma para cada elemento; el ejemplo corresponde a /projects/1/board.");
    }

    @Test
    void showsTheTemplateRouteEvenWhenOnlyOnePageMatchesSoNoRecordIdLeaks() {
        Page project = new Page("a", "http://localhost/projects/1", "FlowPilot", "Project detail", null);

        Document document = generateForPages(Document.DocumentLanguage.EN, project);

        assertThat(document.getSections()).extracting(DocumentSection::getTitle)
                .containsExactly("Projects: Project detail (/projects/{id})");
        assertThat(document.getSections().get(0).getContent())
                .startsWith("The /projects/{id} route displays the \"Project detail\" page.")
                .doesNotContain("/projects/1", "same for every item");
    }

    @Test
    void keepsTheLoginSectionUnchangedAndDoesNotCollapseItWithOtherPages() {
        Page login = new Page("a", "http://localhost/login", "Sign in", "Welcome", PageKind.LOGIN);
        Page other = new Page("a", "http://localhost/users/5", "FlowPilot", null, null);

        Document document = generateForPages(Document.DocumentLanguage.EN, other, login);

        assertThat(document.getSections()).extracting(DocumentSection::getTitle)
                .containsExactly("How to sign in", "Users: FlowPilot (/users/{id})");
        assertThat(document.getSections().get(0).getContent()).startsWith("This screen lets you sign in to the application.");
    }

    @Test
    void doesNotCollapsePagesWithoutAnIdSegment() {
        Page tabA = new Page("a", "http://localhost/projects?tab=a", "FlowPilot", "Projects A", null);
        Page tabB = new Page("a", "http://localhost/projects?tab=b", "FlowPilot", "Projects B", null);
        Page hashOne = new Page("a", "http://localhost/#/reports", "FlowPilot", "Reports", null);
        Page hashTwo = new Page("a", "http://localhost/#/settings", "FlowPilot", "Settings", null);

        Document document = generateForPages(Document.DocumentLanguage.EN, tabA, tabB, hashOne, hashTwo);

        assertThat(document.getSections()).hasSize(4);
        assertThat(document.getSections()).extracting(DocumentSection::getContent).noneMatch(content -> content.contains("same for every item"));
        assertThat(document.getSections()).extracting(DocumentSection::getTitle).noneMatch(title -> title.contains("Item details"));
    }

    @Test
    void doesNotCollapsePagesFromDifferentModulesThatShareATemplate() {
        Page one = new Page("a", "http://localhost/1", "FlowPilot", "One", null);
        Page two = new Page("a", "http://localhost/2", "FlowPilot", "Two", null);

        Document document = generateForPages(Document.DocumentLanguage.EN, one, two);

        assertThat(document.getSections()).hasSize(2);
    }

    @Test
    void addsApprovedElementsFromOtherPagesOfACollapsedSection() {
        Page board1 = new Page("a", "http://localhost/projects/1/board", "FlowPilot");
        Page board2 = new Page("a", "http://localhost/projects/2/board", "FlowPilot");
        UIElement approvedElsewhere = new UIElement(board2.getId(), "button", "#archive", "Archive board", ActionClassification.UNKNOWN);
        approvedElsewhere.setManualInclusionApproved(true);
        UIElement approvedDuplicate = new UIElement(board2.getId(), "link", "a.help", "Help", ActionClassification.UNKNOWN);
        approvedDuplicate.setManualInclusionApproved(true);
        UIElement unapproved = new UIElement(board2.getId(), "button", "#sync", "Sync now", ActionClassification.UNKNOWN);
        Document document = generateForPages(Document.DocumentLanguage.EN, List.of(board1, board2), page -> page == board1
                ? List.of(new UIElement(board1.getId(), "link", "a.help", "Help", ActionClassification.SAFE))
                : List.of(approvedElsewhere, approvedDuplicate, unapproved,
                        new UIElement(board2.getId(), "link", "a.record", "Record specific link", ActionClassification.SAFE)));

        String content = document.getSections().get(0).getContent();
        assertThat(document.getSections()).hasSize(1);
        assertThat(content).contains("\"Help\"").contains("\"Archive board\"")
                .doesNotContain("Sync now").doesNotContain("Record specific link");
        assertThat(content.split("\"Help\"", -1)).hasSize(2);
    }

    @Test
    void keepsApprovalsThatMatchAnExcludedExampleElementOrHaveNoName() {
        Page board1 = new Page("a", "http://localhost/projects/1/board", "FlowPilot");
        Page board2 = new Page("a", "http://localhost/projects/2/board", "FlowPilot");
        UIElement approvedSameName = new UIElement(board2.getId(), "button", "#export", "Export", ActionClassification.UNKNOWN);
        approvedSameName.setManualInclusionApproved(true);
        UIElement approvedUnnamedOne = new UIElement(board2.getId(), "button", "#icon-one", null, ActionClassification.UNKNOWN);
        approvedUnnamedOne.setManualInclusionApproved(true);
        UIElement approvedUnnamedTwo = new UIElement(board2.getId(), "button", "#icon-two", null, ActionClassification.UNKNOWN);
        approvedUnnamedTwo.setManualInclusionApproved(true);
        Document document = generateForPages(Document.DocumentLanguage.EN, List.of(board1, board2), page -> page == board1
                ? List.of(new UIElement(board1.getId(), "button", "#export", "Export", ActionClassification.UNKNOWN))
                : List.of(approvedSameName, approvedUnnamedOne, approvedUnnamedTwo));

        String content = document.getSections().get(0).getContent();
        assertThat(content).contains("\"Export\"");
        assertThat(content.split("\"this control\"", -1)).hasSize(3);
    }

    @Test
    void usesTheExamplePageElementsForACollapsedSection() {
        Page board1 = new Page("a", "http://localhost/projects/1/board", "FlowPilot");
        Page board2 = new Page("a", "http://localhost/projects/2/board", "FlowPilot");
        Document document = generateForPages(Document.DocumentLanguage.EN, List.of(board2, board1), page -> page == board1
                ? List.of(new UIElement(board1.getId(), "link", "a.help", "Help", ActionClassification.SAFE))
                : List.of(new UIElement(board2.getId(), "link", "a.other", "Other page only", ActionClassification.SAFE)));

        assertThat(document.getSections()).hasSize(1);
        assertThat(document.getSections().get(0).getContent()).contains("\"Help\"").doesNotContain("Other page only");
    }

    @Test
    void titlesASpanishSectionWithTheAppOwnNavigationLabelAndTranslatesFixedNames() {
        Page dashboard = new Page("a", "http://localhost/dashboard", "Dashboard");
        Page board = new Page("a", "http://localhost/projects/1/board", "FlowPilot");
        Page landing = new Page("a", "http://localhost/", "Landing");
        Page reports = new Page("a", "http://localhost/monthly-reports", "Reports");
        Document document = generateForPages(Document.DocumentLanguage.ES, List.of(dashboard, board, landing, reports), page -> page == dashboard
                ? List.of(new UIElement(dashboard.getId(), "a", "a:nth-of-type(1)", "Proyectos", ActionClassification.SAFE, "/projects"))
                : List.of());

        assertThat(document.getSections()).extracting(DocumentSection::getTitle).contains(
                "Proyectos: FlowPilot (/projects/{id}/board)", "Inicio: Landing (/)", "Monthly Reports: Reports (/monthly-reports)");
    }

    @Test
    void titlesASectionWithTheAppNavigationLabelInsteadOfMoreFrequentBackLinks() {
        Page dashboard = new Page("a", "http://localhost/dashboard", "Dashboard");
        Page board = new Page("a", "http://localhost/projects/1/board", "FlowPilot");
        Page backlog = new Page("a", "http://localhost/projects/1/backlog", "Backlog");
        Document document = generateForPages(Document.DocumentLanguage.ES, List.of(dashboard, board, backlog), page -> page == dashboard
                ? List.of(new UIElement(dashboard.getId(), "a", "a:nth-of-type(1)", "Proyectos", ActionClassification.SAFE, "/projects", true))
                : List.of(new UIElement(page.getId(), "a", "a:nth-of-type(1)", "Volver a proyectos", ActionClassification.SAFE, "/projects", true)));

        assertThat(document.getSections()).extracting(DocumentSection::getTitle).contains(
                "Proyectos: FlowPilot (/projects/{id}/board)", "Proyectos: Backlog (/projects/{id}/backlog)");
    }

    @Test
    void omitsEmptyInstructionGroups() {
        String content = groupedManualContent(Document.DocumentLanguage.EN, List.of(
                new UIElement("page-1", "link", "a.catalog", "Browse catalog", ActionClassification.SAFE)));

        assertThat(content)
                .contains("Navigation:\n1. Open the \"Browse catalog\" link.")
                .doesNotContain("Information:", "Actions:", "Steps:");
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

        Document result = service(analyses, pages, elements, mock(ScreenshotRepository.class), documents, sections)
                .generate(analysis.getId(), Document.DocumentLanguage.EN, Document.DocumentType.USER_MANUAL, true);

        assertThat(result).isSameAs(existing);
        assertThat(result.getSections().getFirst().getContent())
                .contains("Help", "Press the \"Save\" button.")
                .doesNotContain("Technical reference", "classification", "SAFE", "a.help", "#save", "MUTATING");
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

    @Test
    void getsExistingDraftForAnalysisWithOrderedSections() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Document existing = new Document(analysis.getId(), analysis.getApplicationId());
        DocumentSection section = new DocumentSection(existing.getId(), 0, "page-1", null, "Home", "content");
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.of(existing));
        when(sections.findByDocumentIdOrderByPositionAsc(existing.getId())).thenReturn(List.of(section));

        Document result = service(analyses, mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), documents, sections)
                .getForAnalysis(analysis.getId());

        assertThat(result).isSameAs(existing);
        assertThat(result.getSections()).containsExactly(section);
    }

    @Test
    void rejectsMissingDraftForAnalysisWithoutADocument() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.empty());

        DocumentService service = service(analyses, mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), documents, mock(DocumentSectionRepository.class));

        assertThatThrownBy(() -> service.getForAnalysis(analysis.getId()))
                .isInstanceOf(DocumentService.DocumentNotFoundException.class);
    }

    @Test
    void rejectsDraftLookupForUnknownAnalysis() {
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        when(analyses.findById("missing")).thenReturn(Optional.empty());

        DocumentService service = service(analyses, mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), mock(DocumentRepository.class), mock(DocumentSectionRepository.class));

        assertThatThrownBy(() -> service.getForAnalysis("missing"))
                .isInstanceOf(DocumentService.AnalysisNotFoundException.class);
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

    private static Document generateForPages(Document.DocumentLanguage language, Page... sourcePages) {
        return generateForPages(language, List.of(sourcePages), page -> List.of());
    }

    private static Document generateForPages(Document.DocumentLanguage language, List<Page> sourcePages, java.util.function.Function<Page, List<UIElement>> elementsByPage) {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        UIElementRepository elements = mock(UIElementRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.empty());
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(sourcePages);
        for (Page page : sourcePages) when(elements.findByPageId(page.getId())).thenReturn(elementsByPage.apply(page));
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return service(analyses, pages, elements, mock(ScreenshotRepository.class), documents, sections).generate(analysis.getId(), language);
    }

    private static DocumentService service(AnalysisRepository analyses, PageRepository pages, UIElementRepository elements, ScreenshotRepository screenshots, DocumentRepository documents, DocumentSectionRepository sections) {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(mock(TransactionStatus.class));
        return new DocumentService(analyses, pages, elements, screenshots, documents, sections, testApplications(), transactions);
    }
}
