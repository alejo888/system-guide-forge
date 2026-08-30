package com.systemguideforge.backend.persistence;

import com.systemguideforge.backend.application.ActionClassification;
import com.systemguideforge.backend.application.AnalysisService;
import com.systemguideforge.backend.application.CredentialProtector;
import com.systemguideforge.backend.application.DocumentService;
import com.systemguideforge.backend.application.ScreenAnalysisAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import(DocumentService.class)
@Testcontainers
class PersistenceTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ProjectRepository projects;
    @Autowired TargetApplicationRepository applications;
        @Autowired ExcludedRouteRepository excludedRoutes;
    @Autowired ScreenshotRepository screenshots;
    @Autowired AnalysisRepository analyses;
    @Autowired PageRepository pages;
    @Autowired UIElementRepository uiElements;
    @Autowired DocumentRepository documents;
    @Autowired DocumentSectionRepository documentSections;
    @Autowired DocumentService documentService;

    @Test
    void listsAnalysesNewestFirstAndCountsTheirPages() throws InterruptedException {
        Project project = projects.save(new Project("History project"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "App", "http://localhost:8080", "http://localhost:8080/login", "u", "p"));
        Analysis older = analyses.save(new Analysis(application.getId()));
        pages.save(new Page(older.getId(), "http://localhost:8080/older", "Older"));
        Thread.sleep(2);
        Analysis newer = analyses.save(new Analysis(application.getId()));
        pages.save(new Page(newer.getId(), "http://localhost:8080/newer-1", "Newer"));
        pages.save(new Page(newer.getId(), "http://localhost:8080/newer-2", "Newer"));

        assertThat(analyses.findByApplicationIdOrderByStartedAtDescIdDesc(application.getId()))
                .extracting(Analysis::getId).containsExactly(newer.getId(), older.getId());
        assertThat(pages.countByAnalysisId(newer.getId())).isEqualTo(2);
        assertThat(pages.countByAnalysisId(older.getId())).isEqualTo(1);
    }

    @Test
    void persistsAndRetrievesScreenshotContentExactly() {
        Project project = projects.save(new Project("Screenshot project"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "App", "http://localhost", "http://localhost/login", "u", "p"));
        Analysis analysis = analyses.save(new Analysis(application.getId()));
        Page page = pages.save(new Page(analysis.getId(), "http://localhost/page", "Page"));
        byte[] content = new byte[]{0, 1, 2, (byte) 0xff};
        Screenshot screenshot = screenshots.save(new Screenshot(page.getId(), content));
        Screenshot later = screenshots.save(new Screenshot(page.getId(), new byte[]{9}));

        Screenshot loaded = screenshots.findById(screenshot.getId()).orElseThrow();

        assertThat(loaded.getContent()).containsExactly(content);
        assertThat(screenshots.findByPageIdOrderByIdAsc(page.getId()))
                .extracting(Screenshot::getId).containsExactlyElementsOf(List.of(screenshot.getId(), later.getId()).stream().sorted().toList());
    }


    @Test
    void persistsOrderedDocumentSectionsWithTraceability() {
        Project project = projects.save(new Project("Docs"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "App", "http://localhost:8080", "http://localhost:8080/login", "u", "p"));
        Analysis analysis = new Analysis(application.getId());
        analysis.complete();
        analyses.save(analysis);
        Page page = pages.save(new Page(analysis.getId(), "http://localhost:8080/home", "Home"));
        Screenshot screenshot = screenshots.save(new Screenshot(page.getId(), new byte[]{1, 2}));
        Document document = documents.save(new Document(analysis.getId(), application.getId()));
        documentSections.save(new DocumentSection(document.getId(), 0, page.getId(), screenshot.getId(), "Home", "content"));

        Document loaded = documents.findById(document.getId()).orElseThrow();
        DocumentSection section = documentSections.findByDocumentIdOrderByPositionAsc(loaded.getId()).getFirst();
        assertThat(loaded.getSourceAnalysisId()).isEqualTo(analysis.getId());
        assertThat(section.getSourcePageId()).isEqualTo(page.getId());
        assertThat(section.getScreenshotId()).isEqualTo(screenshot.getId());
    }

    @Test
    void acquiresPessimisticWriteLockForDocumentUpdates() {
        Project project = projects.save(new Project("Lock project"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "Lock app", "http://localhost", "http://localhost/login", "u", "p"));
        Analysis analysis = analyses.save(new Analysis(application.getId()));
        Document document = documents.save(new Document(analysis.getId(), application.getId()));

        assertThat(documents.findByIdForUpdate(document.getId())).containsSame(document);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void serializesConcurrentDocumentUpdatesWithoutMixedOrLostSnapshots() throws Exception {
        Project project = projects.save(new Project("Concurrent docs"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "App", "http://localhost", "http://localhost/login", "u", "p"));
        Analysis analysis = analyses.save(new Analysis(application.getId()));
        Page page = pages.save(new Page(analysis.getId(), "http://localhost/page", "Page"));
        Document document = documents.save(new Document(analysis.getId(), application.getId()));
        DocumentSection section = documentSections.save(new DocumentSection(document.getId(), 0, page.getId(), null, "Original", "original"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> updateAfter(start, document.getId(), section.getId(), "Title A", "Content A"));
            Future<?> second = executor.submit(() -> updateAfter(start, document.getId(), section.getId(), "Title B", "Content B"));
            start.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        DocumentSection finalSection = documentSections.findById(section.getId()).orElseThrow();
        assertThat(List.of("Title A|Content A", "Title B|Content B"))
                .contains(finalSection.getTitle() + "|" + finalSection.getContent());
    }

    private void updateAfter(CountDownLatch start, String documentId, String sectionId, String title, String content) {
        try {
            start.await();
            documentService.update(documentId, new DocumentService.UpdateCommand(title,
                    List.of(new DocumentService.SectionUpdate(sectionId, title, content))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Test
    void updatesDocumentAtomicallyAndPreservesSectionTraceability() {
        Project project = projects.save(new Project("Docs update"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "App", "http://localhost:8080", "http://localhost:8080/login", "u", "p"));
        Analysis analysis = new Analysis(application.getId());
        analyses.save(analysis);
        Page firstPage = pages.save(new Page(analysis.getId(), "http://localhost:8080/first", "First"));
        Page secondPage = pages.save(new Page(analysis.getId(), "http://localhost:8080/second", "Second"));
        Document document = documents.save(new Document(analysis.getId(), application.getId()));
        DocumentSection first = documentSections.save(new DocumentSection(document.getId(), 0, firstPage.getId(), null, "First", "first"));
        DocumentSection second = documentSections.save(new DocumentSection(document.getId(), 1, secondPage.getId(), null, "Second", "second"));

        Document updated = documentService.update(document.getId(), new DocumentService.UpdateCommand("Edited", java.util.List.of(
                new DocumentService.SectionUpdate(second.getId(), "Second edited", "second edited"),
                new DocumentService.SectionUpdate(first.getId(), "First edited", "first edited"))));

        Document loaded = documents.findById(document.getId()).orElseThrow();
        assertThat(loaded.getTitle()).isEqualTo("Edited");
        assertThat(documentSections.findByDocumentIdOrderByPositionAsc(document.getId())).extracting(DocumentSection::getId)
                .containsExactly(second.getId(), first.getId());
        DocumentSection loadedSecond = documentSections.findById(second.getId()).orElseThrow();
        assertThat(loadedSecond.getSourcePageId()).isEqualTo(secondPage.getId());
        assertThat(loadedSecond.getScreenshotId()).isNull();
        assertThat(updated.getSections()).extracting(DocumentSection::getPosition).containsExactly(0, 1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollsBackDocumentAndSectionsWhenSectionGenerationFails() {
            List<String> existingSectionIds = documentSections.findAll().stream().map(DocumentSection::getId).toList();
        Project project = projects.save(new Project("Docs"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "App", "http://localhost:8080", "http://localhost:8080/login", "u", "p"));
        Analysis analysis = new Analysis(application.getId());
        analysis.complete();
        analyses.save(analysis);
        Page page = pages.save(new Page(analysis.getId(), "http://localhost:8080/home", "Home"));
        // The generated section exceeds its database column after the document is inserted.
        for (int i = 0; i < 100; i++) {
            uiElements.save(new UIElement(page.getId(), "button", "#save-" + i, "x".repeat(120), ActionClassification.MUTATING));
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> documentService.generate(analysis.getId()))
                .isInstanceOf(RuntimeException.class);

        assertThat(documents.findBySourceAnalysisId(analysis.getId())).isEmpty();
        assertThat(documentSections.findAll()).extracting(DocumentSection::getId)
                    .containsExactlyInAnyOrderElementsOf(existingSectionIds);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void startsAnalysisWithPersistedExcludedRoutesAfterApplicationSessionCloses() {
        Project project = projects.save(new Project("Analysis project"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "App", "http://localhost", "http://localhost/login", "u", "p", 2, List.of("/admin")));
        CredentialProtector protector = mock(CredentialProtector.class);
        ScreenAnalysisAdapter adapter = mock(ScreenAnalysisAdapter.class);
        when(protector.decrypt(any())).thenReturn("secret");
        when(adapter.analyze(any(), any(), any())).thenAnswer(invocation -> {
            TargetApplication loaded = invocation.getArgument(0);
            assertThat(loaded.isExcludedPath("http://localhost/admin/settings")).isTrue();
            return new ScreenAnalysisAdapter.ScreenAnalysisResult("http://localhost/home", "Home", List.of(), null);
        });

        Analysis result = new AnalysisService(analyses, pages, uiElements, screenshots, applications, protector, adapter).start(application.getId());

        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
    }

    @Test
    void persistsCrawlerConfigurationAndExcludedRoutes() {
            Project project = projects.save(new Project("Crawler project"));
            TargetApplication application = applications.save(new TargetApplication(project.getId(), "App", "http://localhost:8080", "http://localhost:8080/login", "u", "p", 4, List.of("/admin", "/settings")));

            TargetApplication loaded = applications.findById(application.getId()).orElseThrow();
            assertThat(loaded.getMaxCrawlDepth()).isEqualTo(4);
            assertThat(excludedRoutes.findByApplicationIdOrderByPath(application.getId())).extracting(ExcludedRoute::getPath)
                    .containsExactly("/admin", "/settings");
        }

    @Test
    void persistsProjectAndApplicationWithoutPlaintextCredentials() {
        Project project = projects.save(new Project("Local system"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "App", "http://localhost:8080", "http://localhost:8080/login", "encrypted-user", "encrypted-password"));

        TargetApplication loaded = applications.findById(application.getId()).orElseThrow();
        assertThat(loaded.getProjectId()).isEqualTo(project.getId());
        assertThat(loaded.getPasswordEncrypted()).isEqualTo("encrypted-password");
        assertThat(loaded.getPasswordEncrypted()).doesNotContain("plain");
    }
}
