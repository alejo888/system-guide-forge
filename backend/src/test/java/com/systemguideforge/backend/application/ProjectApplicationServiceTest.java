package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.ProjectRepository;
import com.systemguideforge.backend.persistence.TargetApplicationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectApplicationServiceTest {
    @Mock ProjectRepository projects;
    @Mock TargetApplicationRepository applications;
    @Mock CredentialProtector protector;
    @Mock AccessProbe accessProbe;
    @InjectMocks ProjectApplicationService service;

    @Test
    void createsApplicationWithValidatedCrawlerConfiguration() {
        when(projects.existsById("project-id")).thenReturn(true);
        when(protector.encrypt("user")).thenReturn("encrypted-user");
        when(protector.encrypt("password")).thenReturn("encrypted-password");
        when(applications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.createApplication("project-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 4, java.util.List.of("/admin/", "/settings"));

        assertEquals(4, created.getMaxCrawlDepth());
        assertEquals(java.util.List.of("/admin", "/settings"), created.getExcludedRoutes());
    }

    @Test
    void rejectsInvalidCrawlerConfigurationBeforePersistence() {
        assertThrows(IllegalArgumentException.class, () -> service.createApplication(
                "project-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 6, java.util.List.of()));
        verifyNoInteractions(applications, protector);
    }

    @Test
    void normalizesRootAndTrailingSlashExcludedRoutes() {
        when(projects.existsById("project-id")).thenReturn(true);
        when(protector.encrypt("user")).thenReturn("encrypted-user");
        when(protector.encrypt("password")).thenReturn("encrypted-password");
        when(applications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.createApplication("project-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 2,
                java.util.List.of("/", "/admin/", "/settings"));

        assertEquals(java.util.List.of("/", "/admin", "/settings"), created.getExcludedRoutes());
    }

    @Test
    void rejectsMalformedExcludedRoutesBeforePersistence() {
        for (String route : java.util.List.of("admin?x=1", "//admin", "/admin//", "/admin?x=1", "http://localhost/admin", "/a%2Fb", "   ")) {
            assertThrows(IllegalArgumentException.class, () -> service.createApplication(
                    "project-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 2, java.util.List.of(route)));
        }
        verifyNoInteractions(applications, protector);
    }

    @Test
    void rejectsRemoteApplicationUrlBeforePersistence() {
        assertThrows(IllegalArgumentException.class, () -> service.createApplication(
                "project-id", "App", "https://example.com", "https://example.com/login", "user", "password"));
        verifyNoInteractions(applications, protector);
    }

    @Test
    void updatesConfigurationAndReencryptsCredentials() {
        var existing = TestFixtures.application("app-id");
        when(applications.findById("app-id")).thenReturn(java.util.Optional.of(existing));
        when(protector.encrypt("new-user")).thenReturn("encrypted-new-user");
        when(protector.encrypt("new-password")).thenReturn("encrypted-new-password");
        when(applications.save(existing)).thenReturn(existing);

        var updated = service.updateApplication("app-id", "Updated", "http://localhost:9090", "http://localhost:9090/sign-in", "new-user", "new-password", 3, java.util.List.of("/admin/users"));

        assertEquals("Updated", updated.getName());
        assertEquals("http://localhost:9090", updated.getBaseUrl());
        assertEquals("http://localhost:9090/sign-in", updated.getLoginUrl());
        assertEquals("encrypted-new-user", updated.getUsernameEncrypted());
        assertEquals("encrypted-new-password", updated.getPasswordEncrypted());
        assertEquals(3, updated.getMaxCrawlDepth());
        assertEquals(java.util.List.of("/admin/users"), updated.getExcludedRoutes());
        verify(protector, never()).decrypt(any());
        verify(applications).save(existing);
    }

    @Test
    void rejectsMissingApplicationUpdate() {
        when(applications.findById("missing")).thenReturn(java.util.Optional.empty());
        assertThrows(java.util.NoSuchElementException.class, () -> service.updateApplication("missing", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password"));
        verify(applications, never()).save(any());
    }

    @Test
    void rejectsRemoteUpdateUrlBeforePersistence() {
        assertThrows(IllegalArgumentException.class, () -> service.updateApplication("app-id", "App", "https://example.com", "http://localhost:8080/login", "user", "password"));
        verifyNoInteractions(applications, protector);
    }

    @Test
    void rejectsBlankUpdateFieldsBeforePersistence() {
        assertThrows(IllegalArgumentException.class, () -> service.updateApplication("app-id", " ", "http://localhost:8080", "http://localhost:8080/login", "user", "password"));
        verifyNoInteractions(applications, protector);
    }

    @Test
    void accessProbeReceivesCredentialsButResponseContainsOnlyResult() {
        when(applications.findById("app-id")).thenReturn(java.util.Optional.of(TestFixtures.application("app-id")));
        when(protector.decrypt("encrypted-user")).thenReturn("user");
        when(protector.decrypt("encrypted")).thenReturn("password");
        when(accessProbe.test(any())).thenReturn(new AccessResult(true, true, "Access successful"));

        AccessResult result = service.testAccess("app-id");

        assertTrue(result.reachable());
        assertTrue(result.authenticated());
        assertFalse(result.message().contains("password"));
        verify(accessProbe).test(argThat(request -> request.username().equals("user") && request.password().equals("password")));
    }
}
