package com.systemguideforge.backend.persistence;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "document_sections")
public class DocumentSection {
    @Id private String id;
    private String documentId;
    private int position;
    private String sourcePageId;
    private String screenshotId;
    @Column(length = 255, nullable = false) private String title;
    @Column(length = 10000, nullable = false) private String content;
    protected DocumentSection() {}
    public DocumentSection(String documentId, int position, String sourcePageId, String screenshotId, String title, String content) { this.id = UUID.randomUUID().toString(); this.documentId = documentId; this.position = position; this.sourcePageId = sourcePageId; this.screenshotId = screenshotId; this.title = title; this.content = content; }
    public String getId() { return id; }
    public String getDocumentId() { return documentId; }
    public int getPosition() { return position; }
    public String getSourcePageId() { return sourcePageId; }
    public String getScreenshotId() { return screenshotId; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public void updateEditableFields(String title, String content, int position) { this.title = title; this.content = content; this.position = position; }
}
