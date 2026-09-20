package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.Document;
import com.systemguideforge.backend.persistence.DocumentRepository;
import com.systemguideforge.backend.persistence.DocumentSection;
import com.systemguideforge.backend.persistence.DocumentSectionRepository;
import com.systemguideforge.backend.persistence.Screenshot;
import com.systemguideforge.backend.persistence.ScreenshotRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DocxManualExporterTest {
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9JZZsAAAAASUVORK5CYII=");

    @Test
    void exportsPersistedVisibleSectionsInPositionOrderWithSanitizedScreenshots() throws IOException {
        Document document = new Document("analysis-1", "application-1");
        document.updateTitle("Saved guide");
        DocumentSection second = new DocumentSection(document.getId(), 1, "page-2", "screenshot-1", "Second saved", "Second saved content");
        DocumentSection first = new DocumentSection(document.getId(), 0, "page-1", "missing-screenshot", "First saved", "First saved content");
        DocumentSection hidden = new DocumentSection(document.getId(), 2, "page-3", null, "Hidden", "Must not appear");
        hidden.updateEditableFields("Hidden", "Must not appear", 2, true);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        ScreenshotRepository screenshots = mock(ScreenshotRepository.class);
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of(first, second, hidden));
        when(screenshots.findById("missing-screenshot")).thenReturn(Optional.empty());
        when(screenshots.findById("screenshot-1")).thenReturn(Optional.of(new Screenshot("page-2", PNG)));

        ManualExporter.ExportedManual exported = new DocxManualExporter(documents, sections, screenshots).export(document.getId());

        assertThat(exported.title()).isEqualTo("Saved guide");
        assertThat(exported.bytes()).startsWith((byte) 'P', (byte) 'K');
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(exported.bytes()))) {
            String documentXml = null;
            boolean containsScreenshot = false;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("word/document.xml")) documentXml = new String(zip.readAllBytes());
                if (entry.getName().equals("word/media/image1.png")) containsScreenshot = true;
            }
            assertThat(documentXml).contains("Saved guide", "First saved", "First saved content", "Second saved", "Second saved content")
                    .doesNotContain("Hidden", "Must not appear");
            assertThat(documentXml.indexOf("First saved")).isLessThan(documentXml.indexOf("Second saved"));
            assertThat(containsScreenshot).isTrue();
        }
        verify(screenshots).findById("missing-screenshot");
        verify(screenshots).findById("screenshot-1");
    }

    @Test
    void reportsMissingPersistedDocuments() {
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findById("missing")).thenReturn(Optional.empty());

        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(() ->
                new DocxManualExporter(documents, mock(DocumentSectionRepository.class), mock(ScreenshotRepository.class)).export("missing")))
                .isInstanceOf(DocumentService.DocumentNotFoundException.class);
    }
}
