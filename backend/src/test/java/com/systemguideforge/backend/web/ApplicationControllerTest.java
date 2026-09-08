package com.systemguideforge.backend.web;

import com.systemguideforge.backend.application.AccessResult;
import com.systemguideforge.backend.application.ProjectApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApplicationControllerTest {
    private final ApplicationController controller = new ApplicationController(mock(ProjectApplicationService.class));

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
        when(service.testAccess("app-1")).thenReturn(new AccessResult(true, true, "Access successful"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApplicationController(service)).build();

        mvc.perform(post("/api/applications/app-1/test-access"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(true))
                .andExpect(jsonPath("$.authenticated").value(true));
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
