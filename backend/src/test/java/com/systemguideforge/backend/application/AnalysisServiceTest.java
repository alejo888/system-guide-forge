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
        when(adapter.analyze(any(), any(), any())).thenReturn(new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/home", "Home", List.of(), null, List.of(child, duplicate, deep, blocked)));
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
        ScreenAnalysisAdapter adapter=mock(ScreenAnalysisAdapter.class); when(adapter.analyze(any(),any(),any())).thenThrow(new RuntimeException("password=supersecret"));
        Analysis result=new AnalysisService(analyses,mock(PageRepository.class),mock(UIElementRepository.class),mock(ScreenshotRepository.class),apps,mock(CredentialProtector.class),adapter).start(app.getId());
        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.FAILED); assertThat(result.getFailureMessage()).doesNotContain("supersecret");
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
