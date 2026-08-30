package com.systemguideforge.backend.web;

import com.systemguideforge.backend.application.ProjectApplicationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ApplicationControllerTest {
    private final ApplicationController controller = new ApplicationController(mock(ProjectApplicationService.class));

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
