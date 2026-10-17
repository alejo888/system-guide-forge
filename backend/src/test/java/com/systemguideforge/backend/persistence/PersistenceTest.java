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

    @Autowired jakarta.persistence.EntityManager entityManager;
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
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

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
    void persistsAndRetrievesPageHeadingNullableAlongsideTheRawTitle() {
        Project project = projects.save(new Project("Heading project"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "App", "http://localhost", "http://localhost/login", "u", "p"));
        Analysis analysis = analyses.save(new Analysis(application.getId()));
        Page withHeading = pages.save(new Page(analysis.getId(), "http://localhost/board", "App", "Project board", null));
        Page withoutHeading = pages.save(new Page(analysis.getId(), "http://localhost/plain", "App"));
        entityManager.flush();
        entityManager.clear();

        assertThat(pages.findById(withHeading.getId()).orElseThrow().getHeading()).isEqualTo("Project board");
        assertThat(pages.findById(withHeading.getId()).orElseThrow().getTitle()).isEqualTo("App");
        assertThat(pages.findById(withoutHeading.getId()).orElseThrow().getHeading()).isNull();
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
        assertThat(loaded.getType()).isEqualTo(Document.DocumentType.USER_MANUAL);
        assertThat(section.getSourcePageId()).isEqualTo(page.getId());
        assertThat(section.getScreenshotId()).isEqualTo(screenshot.getId());
        assertThat(section.isHidden()).isFalse();
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
                new DocumentService.SectionUpdate(second.getId(), "Second edited", "second edited", true),
                new DocumentService.SectionUpdate(first.getId(), "First edited", "first edited", false))));

        Document loaded = documents.findById(document.getId()).orElseThrow();
        assertThat(loaded.getTitle()).isEqualTo("Edited");
        assertThat(documentSections.findByDocumentIdOrderByPositionAsc(document.getId())).extracting(DocumentSection::getId)
                .containsExactly(second.getId(), first.getId());
        DocumentSection loadedSecond = documentSections.findById(second.getId()).orElseThrow();
        assertThat(loadedSecond.getSourcePageId()).isEqualTo(secondPage.getId());
        assertThat(loadedSecond.getScreenshotId()).isNull();
        assertThat(loadedSecond.isHidden()).isTrue();
        assertThat(updated.getSections()).extracting(DocumentSection::getPosition).containsExactly(0, 1);
    }

    @Test
    void persistsOpenedFormElementsLinkedToTheirOpenerAndDeletesThemWithIt() {
        Project project = projects.save(new Project("Opener project"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "App", "http://localhost:8080", "http://localhost:8080/login", "u", "p"));
        Analysis analysis = analyses.save(new Analysis(application.getId()));
        Page page = pages.save(new Page(analysis.getId(), "http://localhost:8080/board", "Board"));
        UIElement opener = uiElements.save(new UIElement(page.getId(), "button", "button:nth-of-type(1)", "Create task", ActionClassification.MUTATING));
        UIElement opened = uiElements.save(new UIElement(page.getId(), "input", "input:nth-of-type(1)", "Title", ActionClassification.UNKNOWN, null, false, "text", opener.getId()));
        entityManager.flush();
        entityManager.clear();

        assertThat(uiElements.findById(opened.getId()).orElseThrow().getOpenerElementId()).isEqualTo(opener.getId());
        assertThat(uiElements.findById(opener.getId()).orElseThrow().getOpenerElementId()).isNull();

        uiElements.deleteById(opener.getId());
        entityManager.flush();
        entityManager.clear();
        assertThat(uiElements.findByPageId(page.getId())).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void persistsGeneratedSectionsWithinDatabaseBounds() {
        Project project = projects.save(new Project("Docs"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "App", "http://localhost:8080", "http://localhost:8080/login", "u", "p"));
        Analysis analysis = new Analysis(application.getId());
        analysis.complete();
        analyses.save(analysis);
        Page page = pages.save(new Page(analysis.getId(), "http://localhost:8080/home", "Home"));
        for (int i = 0; i < 100; i++) {
            uiElements.save(new UIElement(page.getId(), "button", "#save-" + i, "x".repeat(120), ActionClassification.MUTATING));
        }

        Document generated = documentService.generate(analysis.getId());
        assertThat(generated).isNotNull();
        List<DocumentSection> persistedSections = documentSections.findByDocumentIdOrderByPositionAsc(generated.getId());
        assertThat(persistedSections).isNotEmpty().allSatisfy(section -> {
            assertThat(section.getTitle()).isNotNull().hasSizeLessThanOrEqualTo(255);
            assertThat(section.getContent()).isNotNull().hasSizeLessThanOrEqualTo(10_000);
        });
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
    void reusesKeptExcludedRouteRowsWhenAnUpdatePartiallyOverlapsTheStoredRoutes() {
        Project project = projects.save(new Project("Partial overlap project"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "App", "http://localhost:8080", "http://localhost:8080/login", "u", "p", 2,
                List.of("/admin", "/reports", "/settings")));
        applications.flush();
        entityManager.clear();
        java.util.Map<String, String> idsBefore = excludedRouteIdsByPath(application.getId());

        TargetApplication loaded = applications.findById(application.getId()).orElseThrow();
        loaded.updateConfiguration("App", "http://localhost:8080", "http://localhost:8080/login", "u", "p", 2, List.of("/settings", "/billing", "/admin"));
        // Before the flush the routes keep the order of the update.
        assertThat(loaded.getExcludedRoutes()).containsExactly("/settings", "/billing", "/admin");
        applications.flush();
        entityManager.clear();

        java.util.Map<String, String> idsAfter = excludedRouteIdsByPath(application.getId());
        assertThat(idsAfter).containsOnlyKeys("/admin", "/billing", "/settings");
        assertThat(idsAfter.get("/admin")).isEqualTo(idsBefore.get("/admin"));
        assertThat(idsAfter.get("/settings")).isEqualTo(idsBefore.get("/settings"));
        assertThat(idsAfter.get("/billing")).isNotIn(idsBefore.values());
        // After a reload the rows come back in path order (@OrderBy), not in the order of the update.
        assertThat(applications.findById(application.getId()).orElseThrow().getExcludedRoutes()).containsExactly("/admin", "/billing", "/settings");
    }

    @Test
    void reusesEveryExcludedRouteRowWhenAnUpdateOnlyReordersTheStoredRoutes() {
        Project project = projects.save(new Project("Reorder project"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "App", "http://localhost:8080", "http://localhost:8080/login", "u", "p", 2,
                List.of("/admin", "/reports", "/settings")));
        applications.flush();
        entityManager.clear();
        java.util.Map<String, String> idsBefore = excludedRouteIdsByPath(application.getId());

        TargetApplication loaded = applications.findById(application.getId()).orElseThrow();
        loaded.updateConfiguration("App", "http://localhost:8080", "http://localhost:8080/login", "u", "p", 2, List.of("/settings", "/admin", "/reports"));
        assertThat(loaded.getExcludedRoutes()).containsExactly("/settings", "/admin", "/reports");
        applications.flush();
        entityManager.clear();

        assertThat(excludedRouteIdsByPath(application.getId())).isEqualTo(idsBefore);
        assertThat(applications.findById(application.getId()).orElseThrow().getExcludedRoutes()).containsExactly("/admin", "/reports", "/settings");
    }

    private java.util.Map<String, String> excludedRouteIdsByPath(String applicationId) {
        java.util.Map<String, String> ids = new java.util.LinkedHashMap<>();
        for (ExcludedRoute route : excludedRoutes.findByApplicationIdOrderByPath(applicationId)) ids.put(route.getPath(), route.getId());
        return ids;
    }

    @Test
    void persistsApprovedOpenersAndReplacesThemOnUpdate() {
        Project project = projects.save(new Project("Opener project"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "App", "http://localhost:8080", "http://localhost:8080/login", "u", "p", 2, List.of(),
                List.of(new ApprovedOpenerRule("/projects/{id}/board", "Crear tarea"), new ApprovedOpenerRule("/projects", "Crear proyecto"))));
        applications.flush();
        entityManager.clear();

        TargetApplication loaded = applications.findById(application.getId()).orElseThrow();
        assertThat(loaded.getApprovedOpeners()).containsExactly(
                new ApprovedOpenerRule("/projects", "Crear proyecto"), new ApprovedOpenerRule("/projects/{id}/board", "Crear tarea"));

        loaded.updateConfiguration("App", "http://localhost:8080", "http://localhost:8080/login", "u", "p", 2, List.of(),
                List.of(new ApprovedOpenerRule("/projects", "Crear proyecto")));
        applications.flush();
        entityManager.clear();

        assertThat(applications.findById(application.getId()).orElseThrow().getApprovedOpeners())
                .containsExactly(new ApprovedOpenerRule("/projects", "Crear proyecto"));
        assertThat(entityManager.createNativeQuery("select count(*) from approved_openers where application_id = :id")
                .setParameter("id", application.getId()).getSingleResult()).isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void servesApprovedOpenersFromListAndGetEndpointsOutsideAPersistenceSession() throws Exception {
        Project project = projects.save(new Project("Opener endpoints project"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "Opener endpoints app", "http://localhost:8080", "http://localhost:8080/login", "u", "p", 2,
                List.of("/admin", "/settings"),
                List.of(new ApprovedOpenerRule("/projects/{id}/board", "Crear tarea"), new ApprovedOpenerRule("/projects", "Crear proyecto"))));
        var service = new com.systemguideforge.backend.application.ProjectApplicationService(projects, applications, mock(CredentialProtector.class),
                mock(com.systemguideforge.backend.application.AccessProbe.class));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.systemguideforge.backend.web.ApplicationController(service)).build();

        // This test commits its rows (no surrounding transaction), so it removes them to keep other tests independent of it.
        try {
            assertApprovedOpenersServedOutsideASession(mvc, application);
        } finally {
            applications.deleteById(application.getId());
            projects.deleteById(project.getId());
        }
        assertThat(applications.findById(application.getId())).isEmpty();
        assertThat(entityManager.createNativeQuery("select count(*) from approved_openers where application_id = :id")
                .setParameter("id", application.getId()).getSingleResult()).isEqualTo(0L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void approvesOneOpenerOutsideAPersistenceSessionKeepingOpenersRoutesAndCredentials() throws Exception {
        Project project = projects.save(new Project("Opener approval project"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "Opener approval app", "http://localhost:8080", "http://localhost:8080/login",
                "encrypted-user", "encrypted-password", 3, List.of("/admin", "/settings"), List.of(new ApprovedOpenerRule("/projects", "Crear proyecto"))));
        String openerRowId = (String) entityManager.createNativeQuery("select id from approved_openers where application_id = :id")
                .setParameter("id", application.getId()).getSingleResult();
        CredentialProtector protector = mock(CredentialProtector.class);
        // The service as Spring wires it: its @Transactional methods run inside a transaction, the controller outside one.
        var proxyFactory = new org.springframework.aop.framework.ProxyFactory(new com.systemguideforge.backend.application.ProjectApplicationService(projects, applications,
                protector, mock(com.systemguideforge.backend.application.AccessProbe.class)));
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactionManager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var service = (com.systemguideforge.backend.application.ProjectApplicationService) proxyFactory.getProxy();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.systemguideforge.backend.web.ApplicationController(service)).build();

        // This test commits its rows (no surrounding transaction), so it removes them to keep other tests independent of it.
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/applications/" + application.getId() + "/approved-openers")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"routePattern\":\"/projects/4/board\",\"accessibleName\":\"Crear tarea\"}"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.approvedOpeners.length()").value(2))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.approvedOpeners[1].routePattern").value("/projects/{id}/board"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.excludedRoutes.length()").value(2));
            }

            TargetApplication loaded = applications.findById(application.getId()).orElseThrow();
            assertThat(loaded.getApprovedOpeners()).containsExactly(
                    new ApprovedOpenerRule("/projects", "Crear proyecto"), new ApprovedOpenerRule("/projects/{id}/board", "Crear tarea"));
            assertThat(loaded.getExcludedRoutes()).containsExactly("/admin", "/settings");
            assertThat(loaded.getMaxCrawlDepth()).isEqualTo(3);
            assertThat(loaded.getUsernameEncrypted()).isEqualTo("encrypted-user");
            assertThat(loaded.getPasswordEncrypted()).isEqualTo("encrypted-password");
            assertThat(entityManager.createNativeQuery("select count(*) from approved_openers where application_id = :id and id = :row")
                    .setParameter("id", application.getId()).setParameter("row", openerRowId).getSingleResult()).isEqualTo(1L);
            org.mockito.Mockito.verifyNoInteractions(protector);
        } finally {
            applications.deleteById(application.getId());
            projects.deleteById(project.getId());
        }
        assertThat(entityManager.createNativeQuery("select count(*) from approved_openers where application_id = :id")
                .setParameter("id", application.getId()).getSingleResult()).isEqualTo(0L);
    }

    private void assertApprovedOpenersServedOutsideASession(org.springframework.test.web.servlet.MockMvc mvc, TargetApplication application) throws Exception {
        assertThat(applications.findById(application.getId()).orElseThrow().getApprovedOpeners()).containsExactly(
                new ApprovedOpenerRule("/projects", "Crear proyecto"), new ApprovedOpenerRule("/projects/{id}/board", "Crear tarea"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/applications/" + application.getId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.approvedOpeners.length()").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.approvedOpeners[0].routePattern").value("/projects"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.excludedRoutes.length()").value(2));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/applications"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$[?(@.id == '" + application.getId() + "')].approvedOpeners[1].accessibleName").value("Crear tarea"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$[?(@.id == '" + application.getId() + "')].excludedRoutes.length()").value(2));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentApprovalsOfOneOpenerWithDifferentCasingStoreItOnce() throws Exception {
        withCommittedApplication(List.of(), applicationId -> {
            List<Throwable> outcomes = approveConcurrently(applicationId,
                    new ApprovedOpenerRule("/projects/{id}/board", "Crear tarea"), new ApprovedOpenerRule("/projects/7/board", "CREAR TAREA"));

            assertThat(outcomes).containsOnlyNulls();
            assertThat(applications.findById(applicationId).orElseThrow().getApprovedOpeners()).hasSize(1)
                    .allMatch(rule -> rule.routePattern().equals("/projects/{id}/board") && rule.accessibleName().equalsIgnoreCase("Crear tarea"));
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentApprovalsOfTheSameOpenerBothReturnNormally() throws Exception {
        withCommittedApplication(List.of(), applicationId -> {
            List<Throwable> outcomes = approveConcurrently(applicationId,
                    new ApprovedOpenerRule("/projects", "Crear proyecto"), new ApprovedOpenerRule("/projects", "Crear proyecto"));

            assertThat(outcomes).containsOnlyNulls();
            assertThat(applications.findById(applicationId).orElseThrow().getApprovedOpeners())
                    .containsExactly(new ApprovedOpenerRule("/projects", "Crear proyecto"));
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentApprovalsNeverStoreTheFiftyFirstOpener() throws Exception {
        List<ApprovedOpenerRule> fortyNine = java.util.stream.IntStream.range(0, 49).mapToObj(index -> new ApprovedOpenerRule("/page" + index, "Abrir")).toList();
        withCommittedApplication(fortyNine, applicationId -> {
            List<Throwable> outcomes = approveConcurrently(applicationId, new ApprovedOpenerRule("/page49", "Abrir"), new ApprovedOpenerRule("/page50", "Abrir"));

            // Exactly one approval takes the 50th place; the other is rejected as the 400 "too many" case.
            assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
            assertThat(outcomes).filteredOn(java.util.Objects::nonNull).singleElement()
                    .isInstanceOf(IllegalArgumentException.class).hasFieldOrPropertyWithValue("message", "Too many approved openers");
            assertThat(applications.findById(applicationId).orElseThrow().getApprovedOpeners()).hasSize(50);
        });
    }

    private interface CommittedApplicationCheck { void run(String applicationId) throws Exception; }

    /** Commits an application with the given openers, runs the check and removes the rows (no surrounding test transaction rolls them back). */
    private void withCommittedApplication(List<ApprovedOpenerRule> openers, CommittedApplicationCheck check) throws Exception {
        Project project = projects.save(new Project("Concurrent opener project"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "Concurrent opener app", "http://localhost:8080", "http://localhost:8080/login",
                "u", "p", 2, List.of(), openers));
        try {
            check.run(application.getId());
        } finally {
            applications.deleteById(application.getId());
            projects.deleteById(project.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void serializesAFullUpdateWithAConcurrentOpenerApproval() throws Exception {
        Project project = projects.save(new Project("Concurrent update project"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "Update and approve", "http://localhost:8080", "http://localhost:8080/login",
                "u", "p", 2, List.of("/admin"), List.of(new ApprovedOpenerRule("/projects", "Crear proyecto"))));
        ApprovedOpenerRule board = new ApprovedOpenerRule("/projects/{id}/board", "Crear tarea");
        var service = transactionalService(new java.util.concurrent.CyclicBarrier(2));
        // This test commits its rows (no surrounding transaction), so it removes them to keep other tests independent of it.
        try {
            List<Throwable> outcomes = runConcurrently(
                    () -> service.updateApplication(application.getId(), "Updated", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 2,
                            List.of("/admin"), List.of(new ApprovedOpenerRule("/projects", "Crear proyecto"), board)),
                    () -> service.approveOpener(application.getId(), board));
            assertThat(outcomes).containsOnlyNulls();
            TargetApplication stored = applications.findById(application.getId()).orElseThrow();
            // Either order leaves the same configuration: the update lists the opener and the approval adds it at most once.
            assertThat(stored.getName()).isEqualTo("Updated");
            assertThat(stored.getExcludedRoutes()).containsExactly("/admin");
            assertThat(stored.getApprovedOpeners()).containsExactly(new ApprovedOpenerRule("/projects", "Crear proyecto"), board);
        } finally {
            applications.deleteById(application.getId());
            projects.deleteById(project.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void serializesAShorterUpdateOverloadWithAConcurrentOpenerApproval() throws Exception {
        Project project = projects.save(new Project("Concurrent overload project"));
        TargetApplication application = applications.save(new TargetApplication(project.getId(), "Overload and approve", "http://localhost:8080", "http://localhost:8080/login",
                "u", "p", 2, List.of("/admin"), List.of(new ApprovedOpenerRule("/projects", "Crear proyecto"))));
        ApprovedOpenerRule board = new ApprovedOpenerRule("/projects/{id}/board", "Crear tarea");
        var service = transactionalService(new java.util.concurrent.CyclicBarrier(2));
        // This test commits its rows (no surrounding transaction), so it removes them to keep other tests independent of it.
        try {
            // The 8-argument overload replaces the approved openers with none, so it must lock the application like the full one.
            List<Throwable> outcomes = runConcurrently(
                    () -> service.updateApplication(application.getId(), "Updated", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 2,
                            List.of("/admin")),
                    () -> service.approveOpener(application.getId(), board));
            assertThat(outcomes).containsOnlyNulls();
            TargetApplication stored = applications.findById(application.getId()).orElseThrow();
            assertThat(stored.getName()).isEqualTo("Updated");
            assertThat(stored.getExcludedRoutes()).containsExactly("/admin");
            // Update then approval keeps only the approved opener; approval then update clears every opener.
            assertThat(stored.getApprovedOpeners()).isIn(List.of(board), List.of());
        } finally {
            applications.deleteById(application.getId());
            projects.deleteById(project.getId());
        }
    }

    /** Runs two approvals of one application at the same time; each outcome is null when it returned normally, else what it threw. */
    private List<Throwable> approveConcurrently(String applicationId, ApprovedOpenerRule first, ApprovedOpenerRule second) throws Exception {
        var service = transactionalService(new java.util.concurrent.CyclicBarrier(2));
        return runConcurrently(() -> service.approveOpener(applicationId, first), () -> service.approveOpener(applicationId, second));
    }

    /**
     * The service as Spring wires it (its @Transactional methods run in a transaction) over a repository whose application
     * finders wait for the other request to load the same application too, so both checks would run against the same
     * state unless loading serializes them. A request that cannot reach the barrier (a lock holds it in the database)
     * lets the waiting one go on after a bounded wait instead of failing.
     */
    private com.systemguideforge.backend.application.ProjectApplicationService transactionalService(java.util.concurrent.CyclicBarrier loaded) {
        var repositoryProxy = new org.springframework.aop.framework.ProxyFactory(applications);
        repositoryProxy.addInterface(TargetApplicationRepository.class);
        repositoryProxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().startsWith("find") && result instanceof java.util.Optional<?>) {
                try { loaded.await(2, java.util.concurrent.TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException | java.util.concurrent.BrokenBarrierException ignored) { /* the other request is held by a lock */ }
            }
            return result;
        });
        var repository = (TargetApplicationRepository) repositoryProxy.getProxy();
        CredentialProtector protector = mock(CredentialProtector.class);
        when(protector.encrypt(any())).thenAnswer(invocation -> "encrypted-" + invocation.getArgument(0));
        var proxyFactory = new org.springframework.aop.framework.ProxyFactory(new com.systemguideforge.backend.application.ProjectApplicationService(projects, repository,
                protector, mock(com.systemguideforge.backend.application.AccessProbe.class)));
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactionManager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (com.systemguideforge.backend.application.ProjectApplicationService) proxyFactory.getProxy();
    }

    /** Starts both tasks together and waits a bounded time for each; an outcome is null when the task returned, else what it threw. */
    private List<Throwable> runConcurrently(java.util.concurrent.Callable<?> first, java.util.concurrent.Callable<?> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> futures = java.util.stream.Stream.of(first, second).map(task -> executor.submit(() -> {
                start.await();
                try { task.call(); return (Throwable) null; }
                catch (Throwable error) { return error; }
            })).toList();
            start.countDown();
            java.util.ArrayList<Throwable> outcomes = new java.util.ArrayList<>();
            for (Future<Throwable> future : futures) outcomes.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void listsApplicationsOrderedByNameThenIdWithExcludedRoutesLoaded() {
        Project project = projects.save(new Project("List project"));
        TargetApplication beta = applications.save(new TargetApplication(project.getId(), "Beta", "http://localhost:8080", "http://localhost:8080/login", "u", "p", 2, List.of("/admin", "/settings")));
        TargetApplication alphaOne = applications.save(new TargetApplication(project.getId(), "Alpha", "http://localhost:8081", "http://localhost:8081/login", "u", "p"));
        TargetApplication alphaTwo = applications.save(new TargetApplication(project.getId(), "Alpha", "http://localhost:8082", "http://localhost:8082/login", "u", "p"));
        applications.flush();
        entityManager.clear();

        // Other tests in this class commit their own applications, so only this test's rows are asserted.
        java.util.Set<String> ownIds = java.util.Set.of(beta.getId(), alphaOne.getId(), alphaTwo.getId());
        List<TargetApplication> listed = applications.findAllByOrderByNameAscIdAsc().stream()
                .filter(application -> ownIds.contains(application.getId())).toList();

        List<String> alphaIds = java.util.stream.Stream.of(alphaOne.getId(), alphaTwo.getId()).sorted().toList();
        assertThat(listed).extracting(TargetApplication::getId)
                .containsExactly(alphaIds.get(0), alphaIds.get(1), beta.getId());
        entityManager.clear();
        assertThat(listed.get(2).getExcludedRoutes()).containsExactly("/admin", "/settings");
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
