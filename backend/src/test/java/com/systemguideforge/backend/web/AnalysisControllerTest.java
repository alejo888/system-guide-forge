package com.systemguideforge.backend.web;

import com.systemguideforge.backend.application.ActionClassification;
import com.systemguideforge.backend.application.AnalysisService;
import com.systemguideforge.backend.persistence.UIElement;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AnalysisControllerTest {
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
