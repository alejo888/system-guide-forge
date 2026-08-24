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
    void rejectsRemoteApplicationUrlBeforePersistence() {
        assertThrows(IllegalArgumentException.class, () -> service.createApplication(
                "project-id", "App", "https://example.com", "https://example.com/login", "user", "password"));
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
