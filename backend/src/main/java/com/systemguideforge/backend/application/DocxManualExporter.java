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
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

@Service
public class DocxManualExporter implements ManualExporter {
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private final DocumentRepository documents;
    private final DocumentSectionRepository sections;
    private final ScreenshotRepository screenshots;

    public DocxManualExporter(DocumentRepository documents, DocumentSectionRepository sections, ScreenshotRepository screenshots) {
        this.documents = documents;
        this.sections = sections;
        this.screenshots = screenshots;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportedManual export(String documentId) {
        Document document = documents.findById(documentId).orElseThrow(DocumentService.DocumentNotFoundException::new);
        try (XWPFDocument docx = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeHeading(docx, document.getTitle(), "Title");
            for (DocumentSection section : sections.findByDocumentIdOrderByPositionAsc(documentId)) {
                if (section.isHidden()) continue;
                writeHeading(docx, section.getTitle(), "Heading1");
                writeContent(docx, section.getContent());
                addSanitizedScreenshot(docx, section.getScreenshotId());
            }
            docx.write(output);
            return new ExportedManual(document.getTitle(), output.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create DOCX export", e);
        }
    }

    private static void writeHeading(XWPFDocument docx, String text, String style) {
        XWPFParagraph paragraph = docx.createParagraph();
        paragraph.setStyle(style);
        paragraph.createRun().setText(text);
    }

    private static void writeContent(XWPFDocument docx, String content) {
        XWPFRun run = docx.createParagraph().createRun();
        String[] lines = content.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) run.addBreak();
            run.setText(lines[index]);
        }
    }

    private void addSanitizedScreenshot(XWPFDocument docx, String screenshotId) {
        if (screenshotId == null) return;
        screenshots.findById(screenshotId)
                .filter(Screenshot::isSanitized)
                .map(Screenshot::getContent)
                .filter(DocxManualExporter::isPng)
                .ifPresent(content -> addPng(docx, content));
    }

    private static boolean isPng(byte[] content) {
        return content != null && content.length >= PNG_SIGNATURE.length
                && Arrays.equals(Arrays.copyOf(content, PNG_SIGNATURE.length), PNG_SIGNATURE);
    }

    private static void addPng(XWPFDocument docx, byte[] content) {
        XWPFParagraph paragraph = docx.createParagraph();
        try (ByteArrayInputStream image = new ByteArrayInputStream(content)) {
            paragraph.createRun().addPicture(image, XWPFDocument.PICTURE_TYPE_PNG, "screenshot.png", Units.toEMU(600), Units.toEMU(400));
        } catch (Exception ignored) {
            // Screenshot output is optional; a malformed persisted image must not prevent manual export.
        }
    }
}
