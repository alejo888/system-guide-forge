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
        when(screenshots.findByPageId(alpha.getId())).thenReturn(Optional.of(new Screenshot(alpha.getId(), new byte[]{1})));
        when(screenshots.findByPageId(zulu.getId())).thenReturn(Optional.empty());
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
