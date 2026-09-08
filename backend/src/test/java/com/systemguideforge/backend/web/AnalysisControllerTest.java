package com.systemguideforge.backend.web;

import com.systemguideforge.backend.application.ActionClassification;
import com.systemguideforge.backend.application.AnalysisService;
import com.systemguideforge.backend.persistence.UIElement;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalysisControllerTest {
    @Test
    void returnsNotFoundForMissingAnalysisPagesAndPageElements() throws Exception {
        AnalysisService service = mock(AnalysisService.class);
        when(service.pages("missing-analysis")).thenThrow(new java.util.NoSuchElementException());
        when(service.elements("missing-page")).thenThrow(new java.util.NoSuchElementException());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AnalysisController(service)).build();

        mvc.perform(get("/api/analyses/missing-analysis/pages"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/pages/missing-page/elements"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatesManualInclusionApprovalForUnknownEvidence() {
        AnalysisService service = mock(AnalysisService.class);
        AnalysisController controller = new AnalysisController(service);
        UIElement element = new UIElement("page-1", "button", "#advanced", "Advanced", ActionClassification.UNKNOWN);
        element.setManualInclusionApproved(true);
        when(service.setManualInclusionApproval(element.getId(), true)).thenReturn(element);

        var response = controller.updateManualInclusion(element.getId(), new AnalysisController.ManualInclusionRequest(true));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().manualInclusionApproved()).isTrue();
        verify(service).setManualInclusionApproval(element.getId(), true);
    }
}
