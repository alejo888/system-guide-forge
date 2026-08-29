package com.systemguideforge.backend.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
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

    @Test
    void persistsAndRetrievesScreenshotContentExactly() {
        byte[] content = new byte[]{0, 1, 2, (byte) 0xff};
        Screenshot screenshot = screenshots.save(new Screenshot("page-id", content));

        Screenshot loaded = screenshots.findById(screenshot.getId()).orElseThrow();

        assertThat(loaded.getContent()).containsExactly(content);
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
