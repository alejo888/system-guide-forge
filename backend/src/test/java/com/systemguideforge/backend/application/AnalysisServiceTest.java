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
}
