package com.systemguideforge.backend.persistence;

import com.systemguideforge.backend.application.ActionClassification;
import com.systemguideforge.backend.application.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

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
    @Autowired ScreenshotRepository screenshots;
    @Autowired AnalysisRepository analyses;
    @Autowired PageRepository pages;
    @Autowired UIElementRepository uiElements;
    @Autowired DocumentRepository documents;
    @Autowired DocumentSectionRepository documentSections;
    @Autowired DocumentService documentService;

    @Test
    void persistsAndRetrievesScreenshotContentExactly() {
        byte[] content = new byte[]{0, 1, 2, (byte) 0xff};
        Screenshot screenshot = screenshots.save(new Screenshot("page-id", content));

        Screenshot loaded = screenshots.findById(screenshot.getId()).orElseThrow();

        assertThat(loaded.getContent()).containsExactly(content);
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollsBackDocumentAndSectionsWhenSectionGenerationFails() {
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
        assertThat(documentSections.findAll()).isEmpty();
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
