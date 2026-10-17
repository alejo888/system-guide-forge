package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.ApprovedOpenerRule;
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
    void listsApplicationsInRepositoryOrder() {
        var first = new com.systemguideforge.backend.persistence.TargetApplication("p", "Alpha", "http://localhost", "http://localhost/login", "u", "p");
        var second = new com.systemguideforge.backend.persistence.TargetApplication("p", "Beta", "http://localhost", "http://localhost/login", "u", "p");
        when(applications.findAllByOrderByNameAscIdAsc()).thenReturn(java.util.List.of(first, second));

        assertEquals(java.util.List.of(first, second), service.listApplications());
        verify(applications).findAllByOrderByNameAscIdAsc();
        verifyNoMoreInteractions(applications);
        verifyNoInteractions(protector);
    }

    @Test
    void listsNoApplicationsWhenNoneAreRegistered() {
        when(applications.findAllByOrderByNameAscIdAsc()).thenReturn(java.util.List.of());

        assertTrue(service.listApplications().isEmpty());
    }

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
    void storesApprovedOpenersWithRouteTemplatesAndTrimmedNamesWithoutDuplicates() {
        when(projects.existsById("project-id")).thenReturn(true);
        when(protector.encrypt(any())).thenReturn("encrypted");
        when(applications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.createApplication("project-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 2, java.util.List.of(),
                java.util.List.of(
                        new ApprovedOpenerRule("/projects/1/board/", "  Crear   tarea "),
                        new ApprovedOpenerRule("/projects/{id}/board", "crear tarea"),
                        new ApprovedOpenerRule("/projects/3f2b8c1e-1d2a-4b3c-9d4e-5f6a7b8c9d0e/board", "Crear tarea"),
                        new ApprovedOpenerRule("/projects", "Crear proyecto"),
                        new ApprovedOpenerRule("/projects", "abrir filtros"),
                        new ApprovedOpenerRule("/", "Nuevo")));

        // Sorted by route, then by name ignoring case, the same order a later GET returns; duplicates keep the first spelling.
        assertEquals(java.util.List.of(
                new ApprovedOpenerRule("/", "Nuevo"),
                new ApprovedOpenerRule("/projects", "abrir filtros"),
                new ApprovedOpenerRule("/projects", "Crear proyecto"),
                new ApprovedOpenerRule("/projects/{id}/board", "Crear tarea")), created.getApprovedOpeners());
    }

    @Test
    void acceptsBenignApprovedOpenerNamesIncludingAPlainClave() {
        when(projects.existsById("project-id")).thenReturn(true);
        when(protector.encrypt(any())).thenReturn("encrypted");
        when(applications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.createApplication("project-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 2, java.util.List.of(),
                java.util.List.of(new ApprovedOpenerRule("/projects", "Crear tarea"), new ApprovedOpenerRule("/projects", "Ver clave del proyecto")));

        assertEquals(2, created.getApprovedOpeners().size());
    }

    @Test
    void missingApprovedOpenersOnUpdateClearThemLikeExcludedRoutes() {
        var existing = TestFixtures.application("app-id");
        when(applications.findForUpdateById("app-id")).thenReturn(java.util.Optional.of(existing));
        when(protector.encrypt(any())).thenReturn("encrypted");
        when(applications.save(existing)).thenReturn(existing);
        service.updateApplication("app-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 2, null,
                java.util.List.of(new ApprovedOpenerRule("/projects", "Crear proyecto")));
        assertEquals(1, existing.getApprovedOpeners().size());

        var updated = service.updateApplication("app-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 2, null, null);

        assertTrue(updated.getApprovedOpeners().isEmpty());
        assertTrue(updated.getExcludedRoutes().isEmpty());
    }

    @Test
    void rejectsApprovedOpenersWithRoutesOutsideTheApplicationBeforePersistence() {
        for (String route : java.util.Arrays.asList(null, "", "  ", "projects", "//evil.example/projects", "http://localhost:8080/projects",
                "/projects?tab=1", "/projects#new", "/projects//board", "/a%2Fb", "/projects/{slug}", "/projects/{id", "/" + "a".repeat(200))) {
            var error = assertThrows(IllegalArgumentException.class, () -> service.createApplication(
                    "project-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 2, java.util.List.of(),
                    java.util.List.of(new ApprovedOpenerRule(route, "Crear tarea"))), String.valueOf(route));
            assertTrue(error.getMessage().contains("Approved opener routes"), error.getMessage());
        }
        verifyNoInteractions(applications, protector);
    }

    @Test
    void rejectsBlankLongOrSensitiveApprovedOpenerNamesBeforePersistence() {
        for (String name : java.util.Arrays.asList(null, "", "   ", "x".repeat(121), "Show password", "Copy API key", "Rotate Token", "Client secret", "Credentials",
                "Mostrar contrase" + (char) 0x00F1 + "a", "Mostrar contrasena", "Ver credencial", "Ver credenciales", "Copiar clave de API", "Copiar clave API", "Ver clave secreta", "Revelar secreto")) {
            var error = assertThrows(IllegalArgumentException.class, () -> service.createApplication(
                    "project-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 2, java.util.List.of(),
                    java.util.List.of(new ApprovedOpenerRule("/projects", name))), String.valueOf(name));
            assertTrue(error.getMessage().contains("Approved opener names"), error.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> service.createApplication(
                "project-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 2, java.util.List.of(),
                java.util.Collections.singletonList(null)));
        verifyNoInteractions(applications, protector);
    }

    @Test
    void rejectsTooManyApprovedOpenersBeforePersistence() {
        var openers = java.util.stream.IntStream.range(0, 51).mapToObj(index -> new ApprovedOpenerRule("/page" + index, "Abrir")).toList();

        var error = assertThrows(IllegalArgumentException.class, () -> service.createApplication(
                "project-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 2, java.util.List.of(), openers));

        assertEquals("Too many approved openers", error.getMessage());
        assertEquals("TOO_MANY_APPROVED_OPENERS", ((ProjectApplicationService.InvalidApplicationConfigurationException) error).code());
        verifyNoInteractions(applications, protector);
    }

    @Test
    void approvesOneNormalizedOpenerKeepingTheOtherOpenersRoutesAndCredentials() {
        var existing = TestFixtures.application("app-id");
        existing.updateConfiguration("App", "http://localhost:8080", "http://localhost:8080/login", "encrypted-user", "encrypted", 3, java.util.List.of("/admin"),
                java.util.List.of(new ApprovedOpenerRule("/projects", "Crear proyecto")));
        when(applications.findForUpdateById("app-id")).thenReturn(java.util.Optional.of(existing));
        when(applications.save(existing)).thenReturn(existing);

        var updated = service.approveOpener("app-id", new ApprovedOpenerRule("/projects/7/board/", "  Crear   tarea "));

        assertEquals(java.util.List.of(new ApprovedOpenerRule("/projects", "Crear proyecto"), new ApprovedOpenerRule("/projects/{id}/board", "Crear tarea")),
                updated.getApprovedOpeners());
        assertEquals(java.util.List.of("/admin"), updated.getExcludedRoutes());
        assertEquals(3, updated.getMaxCrawlDepth());
        assertEquals("encrypted-user", updated.getUsernameEncrypted());
        assertEquals("encrypted", updated.getPasswordEncrypted());
        verify(applications).save(existing);
        verifyNoInteractions(protector, accessProbe);
    }

    @Test
    void approvingAnOpenerThatIsAlreadyApprovedChangesNothing() {
        var existing = TestFixtures.application("app-id");
        existing.updateConfiguration("App", "http://localhost:8080", "http://localhost:8080/login", "encrypted-user", "encrypted", 2, java.util.List.of(),
                java.util.List.of(new ApprovedOpenerRule("/projects/{id}/board", "Crear tarea")));
        when(applications.findForUpdateById("app-id")).thenReturn(java.util.Optional.of(existing));

        var updated = service.approveOpener("app-id", new ApprovedOpenerRule("/projects/12/board", "CREAR  TAREA"));

        assertSame(existing, updated);
        assertEquals(java.util.List.of(new ApprovedOpenerRule("/projects/{id}/board", "Crear tarea")), updated.getApprovedOpeners());
        verify(applications, never()).save(any());
        verifyNoInteractions(protector);
    }

    @Test
    void rejectsAnInvalidOpenerApprovalBeforeLoadingTheApplication() {
        for (ApprovedOpenerRule opener : java.util.Arrays.asList(null, new ApprovedOpenerRule("http://localhost:8080/projects", "Crear tarea"),
                new ApprovedOpenerRule("/projects?tab=1", "Crear tarea"), new ApprovedOpenerRule("/projects", "Mostrar contrase" + (char) 0x00F1 + "a"),
                new ApprovedOpenerRule("/projects", " "), new ApprovedOpenerRule("/projects", "x".repeat(121)))) {
            assertThrows(IllegalArgumentException.class, () -> service.approveOpener("app-id", opener), String.valueOf(opener));
        }
        verifyNoInteractions(applications, protector);
    }

    @Test
    void reportsAStableCodeForEachInvalidOpener() {
        assertEquals("INVALID_OPENER_ROUTE", codeOf(() -> service.approveOpener("app-id", new ApprovedOpenerRule("/projects?tab=1", "Crear tarea"))));
        assertEquals("INVALID_OPENER_ROUTE", codeOf(() -> service.approveOpener("app-id", null)));
        assertEquals("INVALID_OPENER_NAME", codeOf(() -> service.approveOpener("app-id", new ApprovedOpenerRule("/projects", "Show password"))));
        assertEquals("TOO_MANY_APPROVED_OPENERS", codeOf(() -> service.createApplication("project-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 2, java.util.List.of(),
                java.util.stream.IntStream.range(0, 51).mapToObj(index -> new ApprovedOpenerRule("/page" + index, "Abrir")).toList())));
    }

    @Test
    void reportsAStableCodeForEachInvalidConfiguration() {
        assertEquals("APPLICATION_FIELDS_REQUIRED", codeOf(() -> service.createApplication("project-id", " ", "http://localhost:8080", "http://localhost:8080/login", "user", "password")));
        assertEquals("INVALID_CRAWL_DEPTH", codeOf(() -> service.createApplication("project-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 6, java.util.List.of())));
        assertEquals("INVALID_EXCLUDED_ROUTE", codeOf(() -> service.createApplication("project-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 2, java.util.List.of("admin"))));
        assertEquals("TOO_MANY_EXCLUDED_ROUTES", codeOf(() -> service.createApplication("project-id", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password", 2,
                java.util.stream.IntStream.range(0, 51).mapToObj(index -> "/route" + index).toList())));
        assertEquals("PROJECT_NOT_FOUND", codeOf(() -> service.createApplication("missing-project", "App", "http://localhost:8080", "http://localhost:8080/login", "user", "password")));
    }

    private static String codeOf(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(ProjectApplicationService.InvalidApplicationConfigurationException.class, call).code();
    }

    @Test
    void rejectsTheFiftyFirstApprovedOpenerButStillAcceptsARepeatedOne() {
        var existing = TestFixtures.application("app-id");
        var fifty = java.util.stream.IntStream.range(0, 50).mapToObj(index -> new ApprovedOpenerRule("/page" + index, "Abrir")).toList();
        existing.updateConfiguration("App", "http://localhost:8080", "http://localhost:8080/login", "encrypted-user", "encrypted", 2, java.util.List.of(), fifty);
        when(applications.findForUpdateById("app-id")).thenReturn(java.util.Optional.of(existing));

        var error = assertThrows(IllegalArgumentException.class, () -> service.approveOpener("app-id", new ApprovedOpenerRule("/page50", "Abrir")));

        assertEquals("Too many approved openers", error.getMessage());
        assertEquals("TOO_MANY_APPROVED_OPENERS", ((ProjectApplicationService.InvalidApplicationConfigurationException) error).code());
        assertEquals(50, service.approveOpener("app-id", new ApprovedOpenerRule("/page7", "abrir")).getApprovedOpeners().size());
        verify(applications, never()).save(any());
    }

    @Test
    void rejectsAnOpenerApprovalForAMissingApplication() {
        when(applications.findForUpdateById("missing")).thenReturn(java.util.Optional.empty());

        assertThrows(java.util.NoSuchElementException.class, () -> service.approveOpener("missing", new ApprovedOpenerRule("/projects", "Crear proyecto")));
        verify(applications, never()).save(any());
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
        when(applications.findForUpdateById("app-id")).thenReturn(java.util.Optional.of(existing));
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
        when(applications.findForUpdateById("missing")).thenReturn(java.util.Optional.empty());
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
