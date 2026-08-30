package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentServiceTest {
    @Test
    void rejectsAnalysisThatIsNotCompleted() {
        Analysis analysis = new Analysis("application-1");
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));

        DocumentService service = service(analyses, mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), mock(DocumentRepository.class), mock(DocumentSectionRepository.class));

        assertThatThrownBy(() -> service.generate(analysis.getId()))
                .isInstanceOf(DocumentService.AnalysisNotCompletedException.class);
    }

    @Test
    void generatesStablePageSectionsWithSourceTraceability() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Page zulu = new Page(analysis.getId(), "http://localhost/z", "Zulu");
        Page alpha = new Page(analysis.getId(), "http://localhost/a", "Alpha");
        UIElement element = new UIElement(alpha.getId(), "button", "#save", "Save", ActionClassification.MUTATING);
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        UIElementRepository elements = mock(UIElementRepository.class);
        ScreenshotRepository screenshots = mock(ScreenshotRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.empty());
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(zulu, alpha));
        when(elements.findByPageId(alpha.getId())).thenReturn(List.of(element));
        when(elements.findByPageId(zulu.getId())).thenReturn(List.of());
        when(screenshots.findByPageIdOrderByIdAsc(alpha.getId())).thenReturn(List.of(new Screenshot(alpha.getId(), new byte[]{1})));
        when(screenshots.findByPageIdOrderByIdAsc(zulu.getId())).thenReturn(List.of());
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Document document = service(analyses, pages, elements, screenshots, documents, sections).generate(analysis.getId());

        assertThat(document.getApplicationId()).isEqualTo("application-1");
        assertThat(document.getSourceAnalysisId()).isEqualTo(analysis.getId());
        assertThat(document.getSections()).extracting(DocumentSection::getTitle).containsExactly("Alpha", "Zulu");
        assertThat(document.getSections().get(0).getSourcePageId()).isEqualTo(alpha.getId());
        assertThat(document.getSections().get(0).getScreenshotId()).isNotNull();
        assertThat(document.getSections().get(0).getContent()).contains("Save", "#save");
    }

    @Test
    void selectsTheStableFirstScreenshotWhenPageHasMultipleScreenshots() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Page page = new Page(analysis.getId(), "http://localhost/home", "Home");
        Screenshot first = mock(Screenshot.class);
        Screenshot second = mock(Screenshot.class);
        when(first.getId()).thenReturn("shot-a");
        when(second.getId()).thenReturn("shot-b");
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        PageRepository pages = mock(PageRepository.class);
        ScreenshotRepository screenshots = mock(ScreenshotRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.empty());
        when(pages.findByAnalysisId(analysis.getId())).thenReturn(List.of(page));
        when(screenshots.findByPageIdOrderByIdAsc(page.getId())).thenReturn(List.of(first, second));
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Document document = service(analyses, pages, mock(UIElementRepository.class), screenshots, documents, sections).generate(analysis.getId());

        assertThat(document.getSections().getFirst().getScreenshotId()).isEqualTo("shot-a");
    }

    @Test
    void updatesEditableFieldsWhilePreservingTraceabilityAndOrder() {
        Document document = new Document("analysis-1", "application-1");
        DocumentSection first = new DocumentSection(document.getId(), 0, "page-1", "shot-1", "First", "first content");
        DocumentSection second = new DocumentSection(document.getId(), 1, "page-2", null, "Second", "second content");
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(documents.findByIdForUpdate(document.getId())).thenReturn(Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of(first, second));
        when(documents.save(document)).thenReturn(document);

        Document result = service(mock(AnalysisRepository.class), mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), documents, sections)
                .update(document.getId(), new DocumentService.UpdateCommand("Edited document", List.of(
                        new DocumentService.SectionUpdate(second.getId(), "Reordered", "new content"),
                        new DocumentService.SectionUpdate(first.getId(), "Updated first", "updated content"))));

        assertThat(result.getTitle()).isEqualTo("Edited document");
        assertThat(result.getSections()).extracting(DocumentSection::getId).containsExactly(second.getId(), first.getId());
        assertThat(result.getSections().get(0).getSourcePageId()).isEqualTo("page-2");
        assertThat(result.getSections().get(0).getScreenshotId()).isNull();
        assertThat(result.getSections().get(0).getTitle()).isEqualTo("Reordered");
        verify(sections, times(2)).flush();
    }

    @Test
    void rejectsInvalidTitleAndSectionSet() {
        Document document = new Document("analysis-1", "application-1");
        DocumentSection section = new DocumentSection(document.getId(), 0, "page-1", null, "Title", "Content");
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(documents.findByIdForUpdate(document.getId())).thenReturn(Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of(section));
        DocumentService service = service(mock(AnalysisRepository.class), mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), documents, sections);

        assertThatThrownBy(() -> service.update(document.getId(), null))
                .isInstanceOf(DocumentService.InvalidDocumentUpdateException.class);
        assertThatThrownBy(() -> service.update(document.getId(), new DocumentService.UpdateCommand("Valid", java.util.Arrays.asList((DocumentService.SectionUpdate) null))))
                .isInstanceOf(DocumentService.InvalidDocumentUpdateException.class);
        assertThatThrownBy(() -> service.update(document.getId(), new DocumentService.UpdateCommand(" ", List.of(new DocumentService.SectionUpdate(section.getId(), "Title", "Content")))))
                .isInstanceOf(DocumentService.InvalidDocumentUpdateException.class);
        assertThatThrownBy(() -> service.update(document.getId(), new DocumentService.UpdateCommand("Valid", List.of())))
                .isInstanceOf(DocumentService.InvalidDocumentUpdateException.class);
        assertThatThrownBy(() -> service.update(document.getId(), new DocumentService.UpdateCommand("Valid", List.of(
                new DocumentService.SectionUpdate(section.getId(), "Title", "x".repeat(10001))))))
                .isInstanceOf(DocumentService.InvalidDocumentUpdateException.class);
        assertThatThrownBy(() -> service.update(document.getId(), new DocumentService.UpdateCommand("Valid", List.of(
                new DocumentService.SectionUpdate(section.getId(), "Title", "Content"),
                new DocumentService.SectionUpdate(section.getId(), "Title", "Content")))))
                .isInstanceOf(DocumentService.InvalidDocumentUpdateException.class);
    }

    @Test
    void rejectsUnknownSectionIds() {
        Document document = new Document("analysis-1", "application-1");
        DocumentSection section = new DocumentSection(document.getId(), 0, "page-1", null, "Title", "Content");
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(documents.findByIdForUpdate(document.getId())).thenReturn(Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of(section));
        DocumentService service = service(mock(AnalysisRepository.class), mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), documents, sections);

        assertThatThrownBy(() -> service.update(document.getId(), new DocumentService.UpdateCommand("Valid", List.of(new DocumentService.SectionUpdate("other", "Title", "Content")))))
                .isInstanceOf(DocumentService.InvalidDocumentUpdateException.class);
    }

    @Test
    void recoversFromConcurrentDuplicateAndReturnsCommittedDraft() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Document existing = new Document(analysis.getId(), analysis.getApplicationId());
        AnalysisRepository analyses = mock(AnalysisRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(documents.findBySourceAnalysisId(analysis.getId()))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(documents.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        Document result = service(analyses, mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), documents, mock(DocumentSectionRepository.class)).generate(analysis.getId());

        assertThat(result).isSameAs(existing);
    }

    @Test
    void reusesExistingDraftForRepeatedGeneration() {
        Analysis analysis = new Analysis("application-1");
        analysis.complete();
        Document existing = new Document(analysis.getId(), analysis.getApplicationId());
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findBySourceAnalysisId(analysis.getId())).thenReturn(Optional.of(existing));

        AnalysisRepository analyses = mock(AnalysisRepository.class);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        Document result = service(analyses, mock(PageRepository.class), mock(UIElementRepository.class), mock(ScreenshotRepository.class), documents, mock(DocumentSectionRepository.class)).generate(analysis.getId());

        assertThat(result).isSameAs(existing);
        verify(documents, never()).save(any());
    }

    private static DocumentService service(AnalysisRepository analyses, PageRepository pages, UIElementRepository elements, ScreenshotRepository screenshots, DocumentRepository documents, DocumentSectionRepository sections) {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(mock(TransactionStatus.class));
        return new DocumentService(analyses, pages, elements, screenshots, documents, sections, transactions);
    }
}
