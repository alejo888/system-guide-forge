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
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

@Service
public class DocxManualExporter implements ManualExporter {
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final int MAX_IMAGE_WIDTH_POINTS = 468;
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
            createHeadingStyles(docx);
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

    private static void createHeadingStyles(XWPFDocument docx) {
        XWPFStyles styles = docx.createStyles();
        addParagraphStyle(styles, "Title", "Title", 44, null);
        addParagraphStyle(styles, "Heading1", "heading 1", 32, 0);
    }

    private static void addParagraphStyle(XWPFStyles styles, String styleId, String name, int fontSizeHalfPoints, Integer outlineLevel) {
        CTStyle ctStyle = CTStyle.Factory.newInstance();
        ctStyle.setStyleId(styleId);
        ctStyle.setType(STStyleType.PARAGRAPH);
        ctStyle.addNewName().setVal(name);
        ctStyle.addNewRPr().addNewB();
        ctStyle.getRPr().addNewSz().setVal(BigInteger.valueOf(fontSizeHalfPoints));
        if (outlineLevel != null) {
            ctStyle.addNewPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(outlineLevel));
        }
        styles.addStyle(new XWPFStyle(ctStyle));
    }

    private static void writeHeading(XWPFDocument docx, String text, String style) {
        XWPFParagraph paragraph = docx.createParagraph();
        paragraph.setStyle(style);
        paragraph.createRun().setText(sanitizeXmlText(text));
    }

    private static void writeContent(XWPFDocument docx, String content) {
        XWPFRun run = docx.createParagraph().createRun();
        String[] lines = sanitizeXmlText(content).split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) run.addBreak();
            run.setText(lines[index]);
        }
    }

    /** Strips characters not allowed in XML 1.0, keeping surrogate pairs (supplementary characters) intact. */
    private static String sanitizeXmlText(String text) {
        if (text == null) return "";
        StringBuilder sanitized = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (isValidXmlChar(current) || Character.isSurrogate(current)) sanitized.append(current);
        }
        return sanitized.toString();
    }

    private static boolean isValidXmlChar(char c) {
        return c == 0x9 || c == 0xA || c == 0xD
                || (c >= 0x20 && c <= 0xD7FF)
                || (c >= 0xE000 && c <= 0xFFFD);
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
            int[] dimensions = pngPixelDimensions(content);
            if (dimensions == null) throw new IllegalArgumentException("Malformed PNG: missing IHDR dimensions");
            int pixelWidth = dimensions[0];
            int pixelHeight = dimensions[1];
            int widthEmu = Units.toEMU(MAX_IMAGE_WIDTH_POINTS);
            int heightEmu = (int) Math.round(widthEmu * ((double) pixelHeight / (double) pixelWidth));
            paragraph.createRun().addPicture(image, XWPFDocument.PICTURE_TYPE_PNG, "screenshot.png", widthEmu, heightEmu);
        } catch (Exception ignored) {
            // Screenshot output is optional; a malformed persisted image must not prevent manual export.
        }
    }

    /** Reads the pixel width/height from a PNG's IHDR chunk (bytes 16-23) without decoding the whole image. */
    private static int[] pngPixelDimensions(byte[] content) {
        if (content == null || content.length < 24) return null;
        int width = readBigEndianInt(content, 16);
        int height = readBigEndianInt(content, 20);
        if (width <= 0 || height <= 0) return null;
        return new int[]{width, height};
    }

    private static int readBigEndianInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }
}
