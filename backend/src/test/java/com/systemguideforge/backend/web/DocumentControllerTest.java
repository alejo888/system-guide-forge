package com.systemguideforge.backend.web;

import com.systemguideforge.backend.application.DocumentService;
import com.systemguideforge.backend.persistence.Document;
import com.systemguideforge.backend.persistence.DocumentSection;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentControllerTest {
    private final DocumentService service = mock(DocumentService.class);
    private final DocumentController controller = new DocumentController(service);

    @Test
    void getsDocumentWithTraceableSections() {
        Document document = document();
        when(service.get(document.getId())).thenReturn(document);

        var response = controller.get(document.getId());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().title()).isEqualTo("Guide");
        assertThat(response.getBody().sections()).extracting(DocumentController.SectionResponse::screenshotId)
                .containsExactly("shot-1");
        assertThat(response.getBody().sections()).extracting(DocumentController.SectionResponse::hidden)
                .containsExactly(false);
    }

    @Test
    void serializesHiddenSectionState() throws Exception {
        Document document = document();
        document.getSections().getFirst().updateEditableFields("Home", "content", 0, true);
        when(service.get(document.getId())).thenReturn(document);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/documents/" + document.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].hidden").value(true));
    }

    @Test
    void mapsGetAndPutMissingDocumentsToNotFound() {
        when(service.get("missing")).thenThrow(new DocumentService.DocumentNotFoundException());
        when(service.update(eq("missing"), any())).thenThrow(new DocumentService.DocumentNotFoundException());

        assertThat(controller.get("missing").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.update("missing", new DocumentController.UpdateRequest("Guide", List.of()))
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void mapsPutValidationAndSuccessResponses() {
        Document document = document();
        when(service.update(eq(document.getId()), any())).thenReturn(document);

        var success = controller.update(document.getId(), new DocumentController.UpdateRequest("Guide", List.of(
                new DocumentController.SectionUpdateRequest("section-1", "Home", "content", true))));
        var invalid = controller.invalidUpdate(new DocumentService.InvalidDocumentUpdateException("invalid"));

        assertThat(success.getStatusCode().value()).isEqualTo(200);
        assertThat(success.getBody().sections()).hasSize(1);
        verify(service).update(eq(document.getId()), argThat(command -> command.sections().getFirst().hidden()));
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(invalid.getBody().message()).isEqualTo("invalid");
    }

    @Test
    void mapsGenerationErrorsAndCreatedResponse() {
        Document document = document();
        when(service.generate("analysis-1", Document.DocumentLanguage.EN, Document.DocumentType.USER_MANUAL, false)).thenReturn(document);

        var created = controller.generate("analysis-1", new DocumentController.GenerateRequest("en", "user_manual"));
        var notFound = controller.analysisNotFound(new DocumentService.AnalysisNotFoundException());
        var incomplete = controller.analysisNotCompleted(new DocumentService.AnalysisNotCompletedException());

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getLocation().toString()).isEqualTo("/api/documents/" + document.getId());
        assertThat(created.getBody().sourceAnalysisId()).isEqualTo("analysis-1");
            assertThat(created.getBody().type()).isEqualTo("user_manual");
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat(incomplete.getStatusCode().value()).isEqualTo(409);
        assertThat(incomplete.getBody().message()).contains("completed analyses");
    }

    @Test
    void forwardsExplicitReplacementConfirmationToTheService() {
        Document document = document();
        when(service.generate("analysis-1", Document.DocumentLanguage.ES, Document.DocumentType.USER_MANUAL, true)).thenReturn(document);

        var response = controller.generate("analysis-1", new DocumentController.GenerateRequest("es", "user_manual", true));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        verify(service).generate("analysis-1", Document.DocumentLanguage.ES, Document.DocumentType.USER_MANUAL, true);
    }

    @Test
    void mapsMissingReplacementConfirmationToConflict() {
        var response = controller.draftReplacementConfirmationRequired(new DocumentService.DraftReplacementConfirmationRequiredException());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().message()).contains("requires confirmation");
        assertThat(response.getBody().code()).isEqualTo("DRAFT_REPLACEMENT_CONFIRMATION_REQUIRED");
    }

    private static Document document() {
        Document document = new Document("analysis-1", "application-1");
        document.updateTitle("Guide");
        document.addSection(new DocumentSection(document.getId(), 0, "page-1", "shot-1", "Home", "content"));
        return document;
    }
}
