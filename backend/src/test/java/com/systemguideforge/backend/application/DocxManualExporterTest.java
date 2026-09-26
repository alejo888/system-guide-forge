package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.Document;
import com.systemguideforge.backend.persistence.DocumentRepository;
import com.systemguideforge.backend.persistence.DocumentSection;
import com.systemguideforge.backend.persistence.DocumentSectionRepository;
import com.systemguideforge.backend.persistence.Screenshot;
import com.systemguideforge.backend.persistence.ScreenshotRepository;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    @Test
    void definesTitleAndHeadingStylesSoWordRendersThemAsHeadings() throws IOException {
        Document document = new Document("analysis-1", "application-1");
        document.updateTitle("Styled guide");
        DocumentSection section = new DocumentSection(document.getId(), 0, "page-1", null, "A section", "Some content");
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of(section));

        ManualExporter.ExportedManual exported = new DocxManualExporter(documents, sections, mock(ScreenshotRepository.class)).export(document.getId());

        try (XWPFDocument reopened = new XWPFDocument(new ByteArrayInputStream(exported.bytes()))) {
            assertThat(reopened.getStyles().styleExist("Title")).isTrue();
            assertThat(reopened.getStyles().styleExist("Heading1")).isTrue();
            List<XWPFParagraph> paragraphs = reopened.getParagraphs();
            assertThat(paragraphs.get(0).getStyle()).isEqualTo("Title");
            assertThat(paragraphs.get(1).getStyle()).isEqualTo("Heading1");
        }
    }

    @Test
    void scalesEmbeddedScreenshotsToFitPageWidthPreservingAspectRatio() throws IOException {
        assertScaledExtentMatchesSource(400, 200);
        assertScaledExtentMatchesSource(200, 400);
        assertScaledExtentMatchesSource(2000, 100);
    }

    private void assertScaledExtentMatchesSource(int pixelWidth, int pixelHeight) throws IOException {
        byte[] png = generatePng(pixelWidth, pixelHeight);
        Document document = new Document("analysis-1", "application-1");
        document.updateTitle("Guide with image");
        DocumentSection section = new DocumentSection(document.getId(), 0, "page-1", "screenshot-1", "Section", "Content");
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        ScreenshotRepository screenshots = mock(ScreenshotRepository.class);
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of(section));
        when(screenshots.findById("screenshot-1")).thenReturn(Optional.of(new Screenshot("page-1", png)));

        ManualExporter.ExportedManual exported = new DocxManualExporter(documents, sections, screenshots).export(document.getId());

        long[] extent = readFirstExtent(exported.bytes());
        assertThat(extent).as("pixel %sx%s", pixelWidth, pixelHeight).isNotNull();
        long cx = extent[0];
        long cy = extent[1];
        assertThat(cx).isLessThanOrEqualTo(Units.toEMU(468));
        double sourceRatio = (double) pixelWidth / pixelHeight;
        double extentRatio = (double) cx / cy;
        assertThat(extentRatio).isCloseTo(sourceRatio, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void capsImageHeightToUsablePageHeightWithoutUpscalingSmallImages() throws IOException {
        long[] tallExtent = exportAndReadExtent(100, 1000);
        assertThat(tallExtent[1]).isLessThanOrEqualTo(Units.toEMU(648));
        double sourceRatio = 100.0 / 1000.0;
        double extentRatio = (double) tallExtent[0] / tallExtent[1];
        assertThat(extentRatio).isCloseTo(sourceRatio, org.assertj.core.data.Offset.offset(0.01));

        long[] smallExtent = exportAndReadExtent(100, 50);
        assertThat(smallExtent[0]).isEqualTo(Units.toEMU(75));
        assertThat(smallExtent[1]).isEqualTo(Units.toEMU(37.5));
    }

    private long[] exportAndReadExtent(int pixelWidth, int pixelHeight) throws IOException {
        byte[] png = generatePng(pixelWidth, pixelHeight);
        Document document = new Document("analysis-1", "application-1");
        document.updateTitle("Guide with image");
        DocumentSection section = new DocumentSection(document.getId(), 0, "page-1", "screenshot-1", "Section", "Content");
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        ScreenshotRepository screenshots = mock(ScreenshotRepository.class);
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of(section));
        when(screenshots.findById("screenshot-1")).thenReturn(Optional.of(new Screenshot("page-1", png)));

        ManualExporter.ExportedManual exported = new DocxManualExporter(documents, sections, screenshots).export(document.getId());

        return readFirstExtent(exported.bytes());
    }

    private static byte[] generatePng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static long[] readFirstExtent(byte[] docxBytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docxBytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("word/document.xml")) {
                    String xml = new String(zip.readAllBytes());
                    Matcher matcher = Pattern.compile("<wp:extent cx=\"(\\d+)\" cy=\"(\\d+)\"").matcher(xml);
                    if (matcher.find()) return new long[]{Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2))};
                }
            }
        }
        return null;
    }

    @Test
    void handlesNullOrBlankTitlesAndContentWithoutFailing() throws IOException {
        Document document = new Document("analysis-1", "application-1");
        document.updateTitle(null);
        DocumentSection nullContentSection = new DocumentSection(document.getId(), 0, "page-1", null, null, null);
        DocumentSection blankSection = new DocumentSection(document.getId(), 1, "page-2", null, "", "");
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of(nullContentSection, blankSection));

        ManualExporter.ExportedManual exported = new DocxManualExporter(documents, sections, mock(ScreenshotRepository.class)).export(document.getId());

        assertThat(exported.bytes()).startsWith((byte) 'P', (byte) 'K');
    }

    @Test
    void stripsXmlInvalidControlCharactersButKeepsSurroundingText() throws IOException {
        Document document = new Document("analysis-1", "application-1");
        document.updateTitle("Guide title");
        String poisoned = "Before\u0000\u0001\u000BAfter";
        DocumentSection section = new DocumentSection(document.getId(), 0, "page-1", null, poisoned, poisoned);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of(section));

        ManualExporter.ExportedManual exported = new DocxManualExporter(documents, sections, mock(ScreenshotRepository.class)).export(document.getId());

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(exported.bytes()))) {
            String documentXml = null;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("word/document.xml")) documentXml = new String(zip.readAllBytes());
            }
            assertThat(documentXml).contains("BeforeAfter");
        }
    }

    @Test
    void excludesUnsanitizedScreenshots() {
        Document document = new Document("analysis-1", "application-1");
        document.updateTitle("Guide");
        DocumentSection section = new DocumentSection(document.getId(), 0, "page-1", "screenshot-1", "Section", "Content");
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        ScreenshotRepository screenshots = mock(ScreenshotRepository.class);
        Screenshot unsanitized = mock(Screenshot.class);
        when(unsanitized.isSanitized()).thenReturn(false);
        when(unsanitized.getContent()).thenReturn(PNG);
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of(section));
        when(screenshots.findById("screenshot-1")).thenReturn(Optional.of(unsanitized));

        ManualExporter.ExportedManual exported = new DocxManualExporter(documents, sections, screenshots).export(document.getId());

        assertThat(containsEmbeddedPicture(exported.bytes())).isFalse();
    }

    @Test
    void sanitizeXmlTextDropsUnpairedSurrogatesButKeepsValidSurrogatePairs() throws IOException {
        Document document = new Document("analysis-1", "application-1");
        document.updateTitle("Guide title");
        String unpairedHigh = "\uD800X"; // heading: lone high surrogate followed by 'X'
        String unpairedLow = "X\uDC00"; // content: 'X' followed by lone low surrogate
        String validPair = "😀"; // 😀 valid surrogate pair, must be preserved
        DocumentSection section = new DocumentSection(document.getId(), 0, "page-1", null,
                unpairedHigh, unpairedLow + validPair);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of(section));

        ManualExporter.ExportedManual exported = new DocxManualExporter(documents, sections, mock(ScreenshotRepository.class)).export(document.getId());

        try (XWPFDocument reopened = new XWPFDocument(new ByteArrayInputStream(exported.bytes()))) {
            List<XWPFParagraph> paragraphs = reopened.getParagraphs();
            assertThat(paragraphs.get(1).getText()).isEqualTo("X");
            assertThat(paragraphs.get(2).getText()).isEqualTo("X" + validPair);
        }
    }

    @Test
    void excludesNonPngScreenshotPayloads() {
        Document document = new Document("analysis-1", "application-1");
        document.updateTitle("Guide");
        byte[] jpegLike = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};
        DocumentSection section = new DocumentSection(document.getId(), 0, "page-1", "screenshot-1", "Section", "Content");
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentSectionRepository sections = mock(DocumentSectionRepository.class);
        ScreenshotRepository screenshots = mock(ScreenshotRepository.class);
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        when(sections.findByDocumentIdOrderByPositionAsc(document.getId())).thenReturn(List.of(section));
        when(screenshots.findById("screenshot-1")).thenReturn(Optional.of(new Screenshot("page-1", jpegLike)));

        ManualExporter.ExportedManual exported = new DocxManualExporter(documents, sections, screenshots).export(document.getId());

        assertThat(containsEmbeddedPicture(exported.bytes())).isFalse();
    }

    private static boolean containsEmbeddedPicture(byte[] docxBytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docxBytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().startsWith("word/media/")) return true;
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return false;
    }

}
