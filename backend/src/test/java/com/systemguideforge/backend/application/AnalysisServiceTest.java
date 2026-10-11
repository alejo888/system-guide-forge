package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AnalysisServiceTest {
    @Test
    void listsDeterministicSummariesForAnExistingApplication() {
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        TargetApplicationRepository applications = mock(TargetApplicationRepository.class);
        TargetApplication app = TestFixtures.application("app-id");
        Analysis newest = new Analysis(app.getId());
        Analysis older = new Analysis(app.getId());
        when(applications.existsById(app.getId())).thenReturn(true);
        when(analyses.findByApplicationIdOrderByStartedAtDescIdDesc(app.getId())).thenReturn(List.of(newest, older));
        when(pages.countByAnalysisId(newest.getId())).thenReturn(3L);
        when(pages.countByAnalysisId(older.getId())).thenReturn(1L);

        AnalysisService service = new AnalysisService(analyses, pages, mock(UIElementRepository.class), mock(ScreenshotRepository.class), applications, mock(CredentialProtector.class), mock(ScreenAnalysisAdapter.class));

        assertThat(service.history(app.getId()))
                .extracting(AnalysisService.AnalysisSummary::id, AnalysisService.AnalysisSummary::pageCount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(newest.getId(), 3L), org.assertj.core.groups.Tuple.tuple(older.getId(), 1L));
        verify(applications).existsById(app.getId());
    }

    @Test
    void derivesModulesForAnExistingAnalysisFromPersistedPages() {
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        TargetApplicationRepository applications = mock(TargetApplicationRepository.class);
            Analysis analysis = new Analysis("app-id");
            when(analyses.findById(analysis.getId())).thenReturn(java.util.Optional.of(analysis));
            Page page = new Page(analysis.getId(), "http://localhost/settings/profile", "Profile");
            when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(page));
            AnalysisService service = new AnalysisService(analyses, pages, mock(UIElementRepository.class), mock(ScreenshotRepository.class), applications, mock(CredentialProtector.class), mock(ScreenAnalysisAdapter.class));
            assertThat(service.modules(analysis.getId()).get(0).pages().get(0).id()).isEqualTo(page.getId());
    }

    @Test
    void ordersPagesWithLoginFirstExplicitlyRegardlessOfRepositoryOrder() {
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        Analysis analysis = new Analysis("app-id");
        when(analyses.findById(analysis.getId())).thenReturn(java.util.Optional.of(analysis));
        Page home = new Page(analysis.getId(), "http://localhost/home", "Home");
        Page login = new Page(analysis.getId(), "http://localhost/login", "Sign in", PageKind.LOGIN);
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(home, login));
        AnalysisService service = new AnalysisService(analyses, pages, mock(UIElementRepository.class), mock(ScreenshotRepository.class), mock(TargetApplicationRepository.class), mock(CredentialProtector.class), mock(ScreenAnalysisAdapter.class));

        assertThat(service.pages(analysis.getId())).extracting(Page::getId).containsExactly(login.getId(), home.getId());
    }

    @Test
    void rejectsPageListingForAnUnknownAnalysis() {
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        when(analyses.findById("missing")).thenReturn(java.util.Optional.empty());
        AnalysisService service = new AnalysisService(analyses, pages, mock(UIElementRepository.class), mock(ScreenshotRepository.class), mock(TargetApplicationRepository.class), mock(CredentialProtector.class), mock(ScreenAnalysisAdapter.class));

        assertThatThrownBy(() -> service.pages("missing")).isInstanceOf(java.util.NoSuchElementException.class);
        verifyNoInteractions(pages);
    }

    @Test
    void rejectsElementListingForAnUnknownPage() {
        PageRepository pages = mock(PageRepository.class);
        UIElementRepository elements = mock(UIElementRepository.class);
        when(pages.findById("missing")).thenReturn(java.util.Optional.empty());
        AnalysisService service = new AnalysisService(mock(AnalysisRepository.class), pages, elements, mock(ScreenshotRepository.class), mock(TargetApplicationRepository.class), mock(CredentialProtector.class), mock(ScreenAnalysisAdapter.class));

        assertThatThrownBy(() -> service.elements("missing")).isInstanceOf(java.util.NoSuchElementException.class);
        verifyNoInteractions(elements);
    }

    @Test
    void rejectsHistoryForAnUnknownApplication() {
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        TargetApplicationRepository applications = mock(TargetApplicationRepository.class);
        when(applications.existsById("missing")).thenReturn(false);
        AnalysisService service = new AnalysisService(analyses, mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), applications, mock(CredentialProtector.class), mock(ScreenAnalysisAdapter.class));

        assertThatThrownBy(() -> service.history("missing")).isInstanceOf(java.util.NoSuchElementException.class);
        verifyNoInteractions(analyses);
    }
    @Test
    void completesAndPersistsOnlySanitizedResults() {
        AnalysisRepository analyses=mock(AnalysisRepository.class); PageRepository pages=mock(PageRepository.class); UIElementRepository elements=mock(UIElementRepository.class); ScreenshotRepository screenshots=mock(ScreenshotRepository.class); TargetApplicationRepository apps=mock(TargetApplicationRepository.class); CredentialProtector protector=mock(CredentialProtector.class); ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class);
        TargetApplication app=new TargetApplication("p","app","http://localhost","http://localhost/login","enc-user","enc-pass");
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app)); when(analyses.existsByStatusIn(any())).thenReturn(false); when(analyses.save(any())).thenAnswer(i->i.getArgument(0)); when(analyses.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); when(protector.decrypt("enc-user")).thenReturn("user"); when(protector.decrypt("enc-pass")).thenReturn("password");
        when(adapter.analyze(app,"user","password")).thenReturn(new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/home","Home",List.of(new ScreenAnalysisAdapter.DetectedElement("button","button:nth-of-type(1)","Save",ActionClassification.MUTATING)),new byte[]{1,2}));
        when(pages.save(any())).thenAnswer(i->i.getArgument(0));
        Analysis result=new AnalysisService(analyses,pages,elements,screenshots,apps,protector,adapter).start(app.getId());
        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.COMPLETED); verify(screenshots).save(any(Screenshot.class)); verify(elements).save(any(UIElement.class));
    }

    @Test
    void persistsTheLinkTargetPathAndNamesModulesFromNavigationLabels() {
        AnalysisRepository analyses=mock(AnalysisRepository.class); PageRepository pages=mock(PageRepository.class); UIElementRepository elements=mock(UIElementRepository.class); ScreenshotRepository screenshots=mock(ScreenshotRepository.class); TargetApplicationRepository apps=mock(TargetApplicationRepository.class); CredentialProtector protector=mock(CredentialProtector.class); ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class);
        TargetApplication app=TestFixtures.application("app-id");
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app)); when(analyses.existsByStatusIn(any())).thenReturn(false); when(analyses.save(any())).thenAnswer(i->i.getArgument(0)); when(analyses.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); when(protector.decrypt(any())).thenReturn("secret"); when(pages.save(any())).thenAnswer(i->i.getArgument(0));
        var nav = new ScreenAnalysisAdapter.DetectedElement("a", "a:nth-of-type(1)", "Proyectos", ActionClassification.SAFE, "/projects");
        when(adapter.analyze(any(), any(), any())).thenReturn(new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/dashboard", "Home", List.of(nav), new byte[]{1}));

        Analysis result=new AnalysisService(analyses,pages,elements,screenshots,apps,protector,adapter).start(app.getId());

        org.mockito.ArgumentCaptor<UIElement> captor = org.mockito.ArgumentCaptor.forClass(UIElement.class);
        verify(elements).save(captor.capture());
        assertThat(captor.getValue().getTargetPath()).isEqualTo("/projects");
        Page dashboard = new Page(result.getId(), "http://localhost/dashboard", "Home"), projects = new Page(result.getId(), "http://localhost/projects", "Projects");
        when(analyses.findById(result.getId())).thenReturn(java.util.Optional.of(result));
        when(pages.findByAnalysisId(result.getId())).thenReturn(List.of(dashboard, projects));
        when(elements.findByPageId(dashboard.getId())).thenReturn(List.of(captor.getValue()));
        assertThat(new AnalysisService(analyses,pages,elements,screenshots,apps,protector,adapter).modules(result.getId())).extracting(FunctionalModuleDeriver.Module::name).containsExactly("Dashboard", "Proyectos");
    }

    @Test
    void persistsTheCapturedInputControlType() {
        AnalysisRepository analyses=mock(AnalysisRepository.class); PageRepository pages=mock(PageRepository.class); UIElementRepository elements=mock(UIElementRepository.class); ScreenshotRepository screenshots=mock(ScreenshotRepository.class); TargetApplicationRepository apps=mock(TargetApplicationRepository.class); CredentialProtector protector=mock(CredentialProtector.class); ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class);
        TargetApplication app=TestFixtures.application("app-id");
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app)); when(analyses.existsByStatusIn(any())).thenReturn(false); when(analyses.save(any())).thenAnswer(i->i.getArgument(0)); when(analyses.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); when(protector.decrypt(any())).thenReturn("secret"); when(pages.save(any())).thenAnswer(i->i.getArgument(0));
        var permission = new ScreenAnalysisAdapter.DetectedElement("input", "input:nth-of-type(1)", "Ver proyectos", ActionClassification.UNKNOWN, null, false, "checkbox");
        when(adapter.analyze(any(), any(), any())).thenReturn(new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/dashboard", "Home", List.of(permission), new byte[]{1}));

        new AnalysisService(analyses,pages,elements,screenshots,apps,protector,adapter).start(app.getId());

        org.mockito.ArgumentCaptor<UIElement> captor = org.mockito.ArgumentCaptor.forClass(UIElement.class);
        verify(elements).save(captor.capture());
        assertThat(captor.getValue().getControlType()).isEqualTo("checkbox");
    }

    @Test
    void persistsLoginPageFirstMarkedAsLoginKindBeforeTheAuthenticatedPage() {
        AnalysisRepository analyses=mock(AnalysisRepository.class); PageRepository pages=mock(PageRepository.class); UIElementRepository elements=mock(UIElementRepository.class); ScreenshotRepository screenshots=mock(ScreenshotRepository.class); TargetApplicationRepository apps=mock(TargetApplicationRepository.class); CredentialProtector protector=mock(CredentialProtector.class); ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class);
        TargetApplication app=TestFixtures.application("app-id");
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app)); when(analyses.existsByStatusIn(any())).thenReturn(false); when(analyses.save(any())).thenAnswer(i->i.getArgument(0)); when(analyses.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); when(protector.decrypt(any())).thenReturn("secret"); when(pages.save(any())).thenAnswer(i->i.getArgument(0));
        var loginPage = new ScreenAnalysisAdapter.LoginPage("http://localhost/login", "Sign in", List.of(), new byte[]{9});
        when(adapter.analyze(any(), any(), any())).thenReturn(new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/home", "Home", List.of(), new byte[]{1}, List.of(), loginPage));

        Analysis result=new AnalysisService(analyses,pages,elements,screenshots,apps,protector,adapter).start(app.getId());

        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        org.mockito.ArgumentCaptor<Page> captor = org.mockito.ArgumentCaptor.forClass(Page.class);
        verify(pages, times(2)).save(captor.capture());
        List<Page> saved = captor.getAllValues();
        assertThat(saved.get(0).getUrl()).isEqualTo("http://localhost/login");
        assertThat(saved.get(0).getKind()).isEqualTo(PageKind.LOGIN);
        assertThat(saved.get(1).getUrl()).isEqualTo("http://localhost/home");
        assertThat(saved.get(1).getKind()).isNull();
    }

    @Test
    void persistsHeadingForStartDiscoveredAndLoginPages() {
        AnalysisRepository analyses=mock(AnalysisRepository.class); PageRepository pages=mock(PageRepository.class); UIElementRepository elements=mock(UIElementRepository.class); ScreenshotRepository screenshots=mock(ScreenshotRepository.class); TargetApplicationRepository apps=mock(TargetApplicationRepository.class); CredentialProtector protector=mock(CredentialProtector.class); ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class);
        TargetApplication app=TestFixtures.application("app-id");
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app)); when(analyses.existsByStatusIn(any())).thenReturn(false); when(analyses.save(any())).thenAnswer(i->i.getArgument(0)); when(analyses.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); when(protector.decrypt(any())).thenReturn("secret"); when(pages.save(any())).thenAnswer(i->i.getArgument(0));
        var loginPage = new ScreenAnalysisAdapter.LoginPage("http://localhost/login", "App", List.of(), new byte[]{9}, "Email", "Secret", "Go", "Welcome back");
        var discovered = new ScreenAnalysisAdapter.DiscoveredPage("http://localhost/projects/1", "App", List.of(), new byte[]{2}, 1, ActionClassification.SAFE, "Project board");
        when(adapter.analyze(any(), any(), any())).thenReturn(new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/home", "App", List.of(), new byte[]{1}, List.of(discovered), loginPage, "Dashboard"));

        new AnalysisService(analyses,pages,elements,screenshots,apps,protector,adapter).start(app.getId());

        org.mockito.ArgumentCaptor<Page> captor = org.mockito.ArgumentCaptor.forClass(Page.class);
        verify(pages, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Page::getHeading).containsExactly("Welcome back", "Dashboard", "Project board");
        assertThat(captor.getAllValues()).extracting(Page::getTitle).containsOnly("App");
    }

    @Test
    void persistsLoginRoleLabelsOnlyOnTheLoginPage() {
        AnalysisRepository analyses=mock(AnalysisRepository.class); PageRepository pages=mock(PageRepository.class); UIElementRepository elements=mock(UIElementRepository.class); ScreenshotRepository screenshots=mock(ScreenshotRepository.class); TargetApplicationRepository apps=mock(TargetApplicationRepository.class); CredentialProtector protector=mock(CredentialProtector.class); ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class);
        TargetApplication app=TestFixtures.application("app-id");
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app)); when(analyses.existsByStatusIn(any())).thenReturn(false); when(analyses.save(any())).thenAnswer(i->i.getArgument(0)); when(analyses.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); when(protector.decrypt(any())).thenReturn("secret"); when(pages.save(any())).thenAnswer(i->i.getArgument(0));
        var loginPage = new ScreenAnalysisAdapter.LoginPage("http://localhost/login", "Sign in", List.of(), new byte[]{9}, "Email", "[redacted]", "Sign in");
        when(adapter.analyze(any(), any(), any())).thenReturn(new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/home", "Home", List.of(), new byte[]{1}, List.of(), loginPage));

        new AnalysisService(analyses,pages,elements,screenshots,apps,protector,adapter).start(app.getId());

        org.mockito.ArgumentCaptor<Page> captor = org.mockito.ArgumentCaptor.forClass(Page.class);
        verify(pages, times(2)).save(captor.capture());
        List<Page> saved = captor.getAllValues();
        assertThat(saved.get(0).getLoginUsernameLabel()).isEqualTo("Email");
        assertThat(saved.get(0).getLoginPasswordLabel()).isEqualTo("[redacted]");
        assertThat(saved.get(0).getLoginSubmitLabel()).isEqualTo("Sign in");
        assertThat(saved.get(1).getLoginUsernameLabel()).isNull();
        assertThat(saved.get(1).getLoginPasswordLabel()).isNull();
        assertThat(saved.get(1).getLoginSubmitLabel()).isNull();
    }

    @Test
    void keepsAuthenticatedLandingPageWhenItSharesTheLoginUrl() {
        AnalysisRepository analyses=mock(AnalysisRepository.class); PageRepository pages=mock(PageRepository.class); UIElementRepository elements=mock(UIElementRepository.class); ScreenshotRepository screenshots=mock(ScreenshotRepository.class); TargetApplicationRepository apps=mock(TargetApplicationRepository.class); CredentialProtector protector=mock(CredentialProtector.class); ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class);
        TargetApplication app=TestFixtures.application("app-id");
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app)); when(analyses.existsByStatusIn(any())).thenReturn(false); when(analyses.save(any())).thenAnswer(i->i.getArgument(0)); when(analyses.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); when(protector.decrypt(any())).thenReturn("secret"); when(pages.save(any())).thenAnswer(i->i.getArgument(0));
        var loginPage = new ScreenAnalysisAdapter.LoginPage("http://localhost/home", "Sign in", List.of(), new byte[]{9});
        when(adapter.analyze(any(), any(), any())).thenReturn(new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/home", "Home", List.of(), new byte[]{1}, List.of(), loginPage));

        Analysis result=new AnalysisService(analyses,pages,elements,screenshots,apps,protector,adapter).start(app.getId());

        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        org.mockito.ArgumentCaptor<Page> captor = org.mockito.ArgumentCaptor.forClass(Page.class);
        verify(pages, times(2)).save(captor.capture());
        List<Page> saved = captor.getAllValues();
        assertThat(saved.get(0).getTitle()).isEqualTo("Sign in");
        assertThat(saved.get(0).getKind()).isEqualTo(PageKind.LOGIN);
        assertThat(saved.get(1).getTitle()).isEqualTo("Home");
        assertThat(saved.get(1).getKind()).isNull();
    }

    @Test
    void countsOnlyPersistedPagesTowardTheCrawlPageBudget() {
        AnalysisRepository analyses=mock(AnalysisRepository.class); PageRepository pages=mock(PageRepository.class); UIElementRepository elements=mock(UIElementRepository.class); ScreenshotRepository screenshots=mock(ScreenshotRepository.class); TargetApplicationRepository apps=mock(TargetApplicationRepository.class); CredentialProtector protector=mock(CredentialProtector.class); ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class);
        TargetApplication app=TestFixtures.application("app-id");
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app)); when(analyses.existsByStatusIn(any())).thenReturn(false); when(analyses.save(any())).thenAnswer(i->i.getArgument(0)); when(analyses.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); when(protector.decrypt(any())).thenReturn("secret"); when(pages.save(any())).thenAnswer(i->i.getArgument(0));
        var loginPage = new ScreenAnalysisAdapter.LoginPage("http://localhost/login", "Sign in", List.of(), null);
        List<ScreenAnalysisAdapter.DiscoveredPage> discovered = new java.util.ArrayList<>();
        for (int i = 0; i < AnalysisService.MAX_CRAWL_PAGES + 5; i++) {
            discovered.add(new ScreenAnalysisAdapter.DiscoveredPage("http://localhost/page-" + i, "Page " + i, List.of(), null, 1, ActionClassification.SAFE));
        }
        when(adapter.analyze(any(), any(), any())).thenReturn(new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/home", "Home", List.of(), null, discovered, loginPage));

        new AnalysisService(analyses,pages,elements,screenshots,apps,protector,adapter).start(app.getId());

        verify(pages, times(AnalysisService.MAX_CRAWL_PAGES)).save(any(Page.class));
    }

    @Test
    void persistsDeduplicatedSafePagesWithinDepthAndSkipsBlockedLinks() {
        AnalysisRepository analyses=mock(AnalysisRepository.class); PageRepository pages=mock(PageRepository.class); UIElementRepository elements=mock(UIElementRepository.class); ScreenshotRepository screenshots=mock(ScreenshotRepository.class); TargetApplicationRepository apps=mock(TargetApplicationRepository.class); CredentialProtector protector=mock(CredentialProtector.class); ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class);
        TargetApplication app=TestFixtures.application("app-id");
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app)); when(analyses.existsByStatusIn(any())).thenReturn(false); when(analyses.save(any())).thenAnswer(i->i.getArgument(0)); when(analyses.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); when(protector.decrypt(any())).thenReturn("secret"); when(pages.save(any())).thenAnswer(i->i.getArgument(0));
        var child = new ScreenAnalysisAdapter.DiscoveredPage("http://localhost/child", "Child", List.of(), null, 1, ActionClassification.SAFE);
        var duplicate = new ScreenAnalysisAdapter.DiscoveredPage("http://localhost/child", "Duplicate", List.of(), null, 1, ActionClassification.SAFE);
        var deep = new ScreenAnalysisAdapter.DiscoveredPage("http://localhost/deep", "Deep", List.of(), null, AnalysisService.MAX_CRAWL_DEPTH + 1, ActionClassification.SAFE);
        var blocked = new ScreenAnalysisAdapter.DiscoveredPage("http://localhost/delete", "Delete", List.of(), null, 1, ActionClassification.MUTATING);
        var unknown = new ScreenAnalysisAdapter.DiscoveredPage("http://localhost/advanced", "Advanced", List.of(), null, 1, ActionClassification.UNKNOWN);
        when(adapter.analyze(any(), any(), any())).thenReturn(new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/home", "Home", List.of(), null, List.of(child, duplicate, deep, blocked, unknown)));
        Analysis result=new AnalysisService(analyses,pages,elements,screenshots,apps,protector,adapter).start(app.getId());
        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.COMPLETED); verify(pages, times(2)).save(any(Page.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5})
    void persistsPagesAtConfiguredDepths(int configuredDepth) {
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        TargetApplicationRepository apps = mock(TargetApplicationRepository.class);
        CredentialProtector protector = mock(CredentialProtector.class);
        ScreenAnalysisAdapter adapter = mock(ScreenAnalysisAdapter.class);
        TargetApplication app = new TargetApplication("project-id", "App", "http://localhost", "http://localhost/login", "enc-user", "enc-pass", configuredDepth, List.of());
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app));
        when(analyses.existsByStatusIn(any())).thenReturn(false);
        when(analyses.save(any())).thenAnswer(i -> i.getArgument(0));
        when(analyses.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(protector.decrypt(any())).thenReturn("secret");
        when(pages.save(any())).thenAnswer(i -> i.getArgument(0));
        var effective = new ScreenAnalysisAdapter.DiscoveredPage("http://localhost/effective-" + configuredDepth, "Effective", List.of(), null, configuredDepth, ActionClassification.SAFE);
        var beyond = new ScreenAnalysisAdapter.DiscoveredPage("http://localhost/beyond-" + configuredDepth, "Beyond", List.of(), null, configuredDepth + 1, ActionClassification.SAFE);
        when(adapter.analyze(any(), any(), any())).thenReturn(new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/home", "Home", List.of(), null, List.of(effective, beyond)));

        Analysis result = new AnalysisService(analyses, pages, mock(UIElementRepository.class), mock(ScreenshotRepository.class), apps, protector, adapter).start(app.getId());

        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        verify(pages, times(2)).save(any(Page.class));
    }

    @Test
    void failsClosedAndDoesNotExposeAdapterSecrets() {
        AnalysisRepository analyses=mock(AnalysisRepository.class); TargetApplicationRepository apps=mock(TargetApplicationRepository.class); TargetApplication app=new TargetApplication("p","app","http://localhost","http://localhost/login","u","p");
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app)); when(analyses.existsByStatusIn(any())).thenReturn(false); when(analyses.save(any())).thenAnswer(i->i.getArgument(0)); when(analyses.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));
        ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class); when(adapter.analyze(any(),any(),any())).thenThrow(new RuntimeException("browser startup failed: password=supersecret https://target.test/home?token=secret"));
        Analysis result=new AnalysisService(analyses,mock(PageRepository.class),mock(UIElementRepository.class),mock(ScreenshotRepository.class),apps,mock(CredentialProtector.class),adapter).start(app.getId());
        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.FAILED); assertThat(result.getFailureMessage()).contains("browser startup failed").doesNotContain("supersecret").doesNotContain("https://target.test/home?token=secret");
    }

    @Test
    void approvesUnknownElementForManualDocumentationAndInvalidatesItsDraft() {
        Page page = new Page("analysis-1", "http://localhost/home", "Home");
        UIElement element = new UIElement(page.getId(), "button", "#advanced", "Advanced", ActionClassification.UNKNOWN);
        Document document = new Document(page.getAnalysisId(), "application-1");
        UIElementRepository elements = mock(UIElementRepository.class);
        PageRepository pages = mock(PageRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(elements.findById(element.getId())).thenReturn(java.util.Optional.of(element));
        when(elements.save(element)).thenReturn(element);
        when(pages.findById(page.getId())).thenReturn(java.util.Optional.of(page));
        when(documents.findBySourceAnalysisId(page.getAnalysisId())).thenReturn(java.util.Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of());

        UIElement approved = new AnalysisService(mock(AnalysisRepository.class), pages, elements, mock(ScreenshotRepository.class), mock(TargetApplicationRepository.class), mock(CredentialProtector.class), mock(ScreenAnalysisAdapter.class), documents, sections)
                .setManualInclusionApproval(element.getId(), true);

        assertThat(approved.isManualInclusionApproved()).isTrue();
        verify(elements).save(element);
        verify(sections).deleteAll(List.of());
        verify(documents).delete(document);
    }

    @Test
    void rejectsManualDocumentationApprovalForNonUnknownElements() {
        UIElement element = new UIElement("page-1", "button", "#save", "Save", ActionClassification.MUTATING);
        UIElementRepository elements = mock(UIElementRepository.class);
        when(elements.findById(element.getId())).thenReturn(java.util.Optional.of(element));

        AnalysisService service = new AnalysisService(mock(AnalysisRepository.class), mock(PageRepository.class), elements, mock(ScreenshotRepository.class), mock(TargetApplicationRepository.class), mock(CredentialProtector.class), mock(ScreenAnalysisAdapter.class), mock(DocumentRepository.class), mock(DocumentSectionRepository.class));

        assertThatThrownBy(() -> service.setManualInclusionApproval(element.getId(), true))
                .isInstanceOf(AnalysisService.InvalidManualInclusionApprovalException.class);
    }

    @Test
    void enforcesGlobalPageBudgetWhenPersistingCrawlerResults() {
        AnalysisRepository analyses=mock(AnalysisRepository.class); PageRepository pages=mock(PageRepository.class); UIElementRepository elements=mock(UIElementRepository.class); ScreenshotRepository screenshots=mock(ScreenshotRepository.class); TargetApplicationRepository apps=mock(TargetApplicationRepository.class); CredentialProtector protector=mock(CredentialProtector.class); ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class);
        TargetApplication app=TestFixtures.application("app-id");
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app)); when(analyses.existsByStatusIn(any())).thenReturn(false); when(analyses.save(any())).thenAnswer(i->i.getArgument(0)); when(analyses.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); when(protector.decrypt(any())).thenReturn("secret"); when(pages.save(any())).thenAnswer(i->i.getArgument(0));
        var pagesFromCrawler = java.util.stream.IntStream.range(0, AnalysisService.MAX_CRAWL_PAGES + 25)
                .mapToObj(i -> new ScreenAnalysisAdapter.DiscoveredPage("http://localhost/page-" + i, "Page", List.of(), null, 1, ActionClassification.SAFE)).toList();
        when(adapter.analyze(any(), any(), any())).thenReturn(new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/home", "Home", List.of(), null, pagesFromCrawler));
        Analysis result=new AnalysisService(analyses,pages,elements,screenshots,apps,protector,adapter).start(app.getId());
        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        verify(pages, times(AnalysisService.MAX_CRAWL_PAGES)).save(any(Page.class));
    }

    @Test
    void persistsOpenedFormElementsOnTheSamePageLinkedToTheirApprovedOpener() {
        AnalysisRepository analyses=mock(AnalysisRepository.class); PageRepository pages=mock(PageRepository.class); UIElementRepository elements=mock(UIElementRepository.class); ScreenshotRepository screenshots=mock(ScreenshotRepository.class); TargetApplicationRepository apps=mock(TargetApplicationRepository.class); CredentialProtector protector=mock(CredentialProtector.class); ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class);
        TargetApplication app=TestFixtures.application("app-id");
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app)); when(analyses.existsByStatusIn(any())).thenReturn(false); when(analyses.save(any())).thenAnswer(i->i.getArgument(0)); when(analyses.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); when(protector.decrypt(any())).thenReturn("secret"); when(pages.save(any())).thenAnswer(i->i.getArgument(0));
        var help = new ScreenAnalysisAdapter.DetectedElement("a", "a:nth-of-type(1)", "Help", ActionClassification.SAFE, "/help");
        var createTask = new ScreenAnalysisAdapter.DetectedElement("button", "button:nth-of-type(2)", "Crear tarea", ActionClassification.MUTATING);
        var title = new ScreenAnalysisAdapter.DetectedElement("input", "input:nth-of-type(1)", "Title", ActionClassification.UNKNOWN, null, false, "text");
        var column = new ScreenAnalysisAdapter.DetectedElement("select", "select:nth-of-type(1)", "Column", ActionClassification.UNKNOWN);
        var createProject = new ScreenAnalysisAdapter.DetectedElement("button", "button:nth-of-type(1)", "Crear proyecto", ActionClassification.MUTATING);
        var projectName = new ScreenAnalysisAdapter.DetectedElement("input", "input:nth-of-type(1)", "Name", ActionClassification.UNKNOWN, null, false, "text");
        var projects = new ScreenAnalysisAdapter.DiscoveredPage("http://localhost/projects", "Projects", List.of(createProject), null, 1, ActionClassification.SAFE, "Projects",
                List.of(new ScreenAnalysisAdapter.OpenedForm("Crear proyecto", "button:nth-of-type(1)", List.of(projectName))));
        when(adapter.analyze(any(), any(), any())).thenReturn(new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/board", "Board", List.of(help, createTask), null, List.of(projects), null, "Board",
                List.of(new ScreenAnalysisAdapter.OpenedForm("Crear tarea", "button:nth-of-type(2)", List.of(title, column)))));

        Analysis result=new AnalysisService(analyses,pages,elements,screenshots,apps,protector,adapter).start(app.getId());

        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        org.mockito.ArgumentCaptor<UIElement> captor = org.mockito.ArgumentCaptor.forClass(UIElement.class);
        verify(elements, times(6)).save(captor.capture());
        List<UIElement> saved = captor.getAllValues();
        assertThat(saved).extracting(UIElement::getAccessibleName).containsExactly("Help", "Crear tarea", "Title", "Column", "Crear proyecto", "Name");
        UIElement boardOpener = saved.get(1), projectOpener = saved.get(4);
        assertThat(List.of(saved.get(0), boardOpener, projectOpener)).extracting(UIElement::getOpenerElementId).containsOnlyNulls();
        assertThat(List.of(saved.get(2), saved.get(3))).allSatisfy(element -> {
            assertThat(element.getOpenerElementId()).isEqualTo(boardOpener.getId());
            assertThat(element.getPageId()).isEqualTo(boardOpener.getPageId());
        });
        assertThat(saved.get(2).getControlType()).isEqualTo("text");
        assertThat(saved.get(3).getKind()).isEqualTo("select");
        assertThat(saved.get(5).getOpenerElementId()).isEqualTo(projectOpener.getId());
        assertThat(saved.get(5).getPageId()).isEqualTo(projectOpener.getPageId()).isNotEqualTo(boardOpener.getPageId());
    }

    @Test
    void skipsAnOpenedFormWhoseOpenerIsNotAPersistedButtonOfThePage() {
        AnalysisRepository analyses=mock(AnalysisRepository.class); PageRepository pages=mock(PageRepository.class); UIElementRepository elements=mock(UIElementRepository.class); ScreenshotRepository screenshots=mock(ScreenshotRepository.class); TargetApplicationRepository apps=mock(TargetApplicationRepository.class); CredentialProtector protector=mock(CredentialProtector.class); ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class);
        TargetApplication app=TestFixtures.application("app-id");
        when(apps.findById(app.getId())).thenReturn(java.util.Optional.of(app)); when(analyses.existsByStatusIn(any())).thenReturn(false); when(analyses.save(any())).thenAnswer(i->i.getArgument(0)); when(analyses.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); when(protector.decrypt(any())).thenReturn("secret"); when(pages.save(any())).thenAnswer(i->i.getArgument(0));
        var createTask = new ScreenAnalysisAdapter.DetectedElement("button", "button:nth-of-type(1)", "Crear tarea", ActionClassification.MUTATING);
        // Same selector text as the second form's opener, but not a button: never a valid opener link.
        var link = new ScreenAnalysisAdapter.DetectedElement("a", "button:nth-of-type(3)", "Help", ActionClassification.SAFE, "/help");
        var orphan = new ScreenAnalysisAdapter.DetectedElement("input", "input:nth-of-type(1)", "Title", ActionClassification.UNKNOWN, null, false, "text");
        var behindALink = new ScreenAnalysisAdapter.DetectedElement("input", "input:nth-of-type(2)", "Description", ActionClassification.UNKNOWN, null, false, "text");
        when(adapter.analyze(any(), any(), any())).thenReturn(new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/board", "Board", List.of(createTask, link), null, List.of(), null, "Board",
                List.of(new ScreenAnalysisAdapter.OpenedForm("Crear tarea", "button:nth-of-type(2)", List.of(orphan)),
                        new ScreenAnalysisAdapter.OpenedForm("Help", "button:nth-of-type(3)", List.of(behindALink)))));

        Analysis result=new AnalysisService(analyses,pages,elements,screenshots,apps,protector,adapter).start(app.getId());

        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        org.mockito.ArgumentCaptor<UIElement> captor = org.mockito.ArgumentCaptor.forClass(UIElement.class);
        verify(elements, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(UIElement::getAccessibleName).containsExactly("Crear tarea", "Help");
        assertThat(captor.getAllValues()).extracting(UIElement::getOpenerElementId).containsOnlyNulls();
    }

    @Test
    void listsAndDerivesModulesOnlyFromPageElementsAndNotFromOpenedFormElements() {
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        UIElementRepository elements = mock(UIElementRepository.class);
        Analysis analysis = new Analysis("app-id");
        Page page = new Page(analysis.getId(), "http://localhost/dashboard", "Home");
        UIElement opener = new UIElement(page.getId(), "button", "button:nth-of-type(1)", "Crear tarea", ActionClassification.MUTATING);
        // An opened element that looks like a navigation link must neither be listed nor name a module yet.
        UIElement opened = new UIElement(page.getId(), "a", "a:nth-of-type(1)", "Proyectos", ActionClassification.SAFE, "/projects", true, null, opener.getId());
        when(analyses.findById(analysis.getId())).thenReturn(java.util.Optional.of(analysis));
        when(pages.findById(page.getId())).thenReturn(java.util.Optional.of(page));
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(page, new Page(analysis.getId(), "http://localhost/projects", "Projects")));
        when(elements.findByPageId(page.getId())).thenReturn(List.of(opener, opened));
        AnalysisService service = new AnalysisService(analyses, pages, elements, mock(ScreenshotRepository.class), mock(TargetApplicationRepository.class), mock(CredentialProtector.class), mock(ScreenAnalysisAdapter.class));

        assertThat(service.elements(page.getId())).containsExactly(opener);
        assertThat(service.modules(analysis.getId())).extracting(FunctionalModuleDeriver.Module::name).containsExactly("Dashboard", "Projects");
    }
}
