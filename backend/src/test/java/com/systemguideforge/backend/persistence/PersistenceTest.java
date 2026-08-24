package com.systemguideforge.backend.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PersistenceTest {
    @Autowired ProjectRepository projects;
    @Autowired TargetApplicationRepository applications;

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
