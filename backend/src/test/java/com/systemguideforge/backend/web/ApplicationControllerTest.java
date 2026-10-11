package com.systemguideforge.backend.web;

import com.systemguideforge.backend.application.AccessResult;
import com.systemguideforge.backend.application.AccessResultCode;
import com.systemguideforge.backend.application.ProjectApplicationService;
import com.systemguideforge.backend.persistence.ApprovedOpenerRule;
import com.systemguideforge.backend.persistence.TargetApplication;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApplicationControllerTest {
    private final ApplicationController controller = new ApplicationController(mock(ProjectApplicationService.class));

    @Test
    void listsApplicationsInServiceOrderWithoutCredentials() throws Exception {
        ProjectApplicationService service = mock(ProjectApplicationService.class);
        when(service.listApplications()).thenReturn(java.util.List.of(
                new TargetApplication("project-1", "Alpha", "http://localhost:8080", "http://localhost:8080/login", "encrypted-user", "encrypted-password", 3, java.util.List.of("/admin")),
                new TargetApplication("project-1", "Beta", "http://localhost:9090", "http://localhost:9090/login", "encrypted-user", "encrypted-password")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApplicationController(service)).build();

        mvc.perform(get("/api/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Alpha"))
                .andExpect(jsonPath("$[0].projectId").value("project-1"))
                .andExpect(jsonPath("$[0].baseUrl").value("http://localhost:8080"))
                .andExpect(jsonPath("$[0].loginUrl").value("http://localhost:8080/login"))
                .andExpect(jsonPath("$[0].maxCrawlDepth").value(3))
                .andExpect(jsonPath("$[0].excludedRoutes[0]").value("/admin"))
                .andExpect(jsonPath("$[1].name").value("Beta"))
                .andExpect(jsonPath("$[0].username").doesNotExist())
                .andExpect(jsonPath("$[0].password").doesNotExist())
                .andExpect(jsonPath("$[0].usernameEncrypted").doesNotExist())
                .andExpect(jsonPath("$[0].passwordEncrypted").doesNotExist())
                .andExpect(jsonPath("$[1].passwordEncrypted").doesNotExist());
    }

    @Test
    void passesApprovedOpenersToTheServiceAndReturnsThemWithoutCredentials() throws Exception {
        ProjectApplicationService service = mock(ProjectApplicationService.class);
        List<ApprovedOpenerRule> openers = List.of(new ApprovedOpenerRule("/projects/{id}/board", "Crear tarea"));
        TargetApplication saved = new TargetApplication("project-1", "Alpha", "http://localhost:8080", "http://localhost:8080/login", "encrypted-user", "encrypted-password", 2, List.of(), openers);
        when(service.updateApplication(eq("app-1"), eq("Alpha"), eq("http://localhost:8080"), eq("http://localhost:8080/login"), eq("user"), eq("password"), eq(2), eq(List.of()),
                eq(List.of(new ApprovedOpenerRule("/projects/1/board", "Crear tarea"))))).thenReturn(saved);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApplicationController(service)).build();

        mvc.perform(put("/api/applications/app-1").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Alpha","baseUrl":"http://localhost:8080","loginUrl":"http://localhost:8080/login","username":"user","password":"password",
                         "maxCrawlDepth":2,"excludedRoutes":[],"approvedOpeners":[{"routePattern":"/projects/1/board","accessibleName":"Crear tarea"}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedOpeners.length()").value(1))
                .andExpect(jsonPath("$.approvedOpeners[0].routePattern").value("/projects/{id}/board"))
                .andExpect(jsonPath("$.approvedOpeners[0].accessibleName").value("Crear tarea"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void passesMissingApprovedOpenersAsNullAndListsEmptyOpeners() throws Exception {
        ProjectApplicationService service = mock(ProjectApplicationService.class);
        TargetApplication saved = new TargetApplication("project-1", "Alpha", "http://localhost:8080", "http://localhost:8080/login", "encrypted-user", "encrypted-password");
        when(service.createApplication(eq("project-1"), eq("Alpha"), eq("http://localhost:8080"), eq("http://localhost:8080/login"), eq("user"), eq("password"), isNull(), isNull(), isNull()))
                .thenReturn(saved);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApplicationController(service)).build();

        mvc.perform(post("/api/projects/project-1/applications").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Alpha","baseUrl":"http://localhost:8080","loginUrl":"http://localhost:8080/login","username":"user","password":"password"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.approvedOpeners").isArray())
                .andExpect(jsonPath("$.approvedOpeners.length()").value(0));
    }

    @Test
    void listsAnEmptyArrayWhenNoApplicationsAreRegistered() throws Exception {
        ProjectApplicationService service = mock(ProjectApplicationService.class);
        when(service.listApplications()).thenReturn(java.util.List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApplicationController(service)).build();

        mvc.perform(get("/api/applications"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", true));
    }

    @Test
    void returnsNotFoundForMissingAccessTestApplication() throws Exception {
        ProjectApplicationService service = mock(ProjectApplicationService.class);
        when(service.testAccess("missing")).thenThrow(new java.util.NoSuchElementException());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApplicationController(service)).build();

        mvc.perform(post("/api/applications/missing/test-access"))
                .andExpect(status().isNotFound());
    }

    @Test
    void serializesAccessTestAuthenticationState() throws Exception {
        ProjectApplicationService service = mock(ProjectApplicationService.class);
        when(service.testAccess("app-1")).thenReturn(new AccessResult(true, true, "Access successful", AccessResultCode.AUTHENTICATED));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApplicationController(service)).build();

        mvc.perform(post("/api/applications/app-1/test-access"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(true))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.code").value("AUTHENTICATED"));
    }

    @Test
    void mapsInvalidCrawlerConfigurationToBadRequest() {
        var response = controller.invalidConfiguration(new IllegalArgumentException("Crawl depth must be between 0 and 5"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("Crawl depth must be between 0 and 5");
    }

    @Test
    void mapsInvalidExcludedRoutesToBadRequest() {
        var response = controller.invalidConfiguration(new IllegalArgumentException("Excluded routes must be normalized URL paths"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().message()).contains("normalized URL paths");
    }
}
