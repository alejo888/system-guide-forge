package com.systemguideforge.backend.persistence;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "documents", uniqueConstraints = @UniqueConstraint(name = "uk_documents_source_analysis", columnNames = "source_analysis_id"))
public class Document {
    @Id private String id;
    private String applicationId;
    private String sourceAnalysisId;
    @Column(nullable = false, length = 255) private String title;
    @Enumerated(EnumType.STRING) private DocumentStatus status;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 2) private DocumentLanguage language;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private DocumentType type;
    @Transient private List<DocumentSection> sections = new ArrayList<>();
    protected Document() {}
    public Document(String sourceAnalysisId, String applicationId) { this(sourceAnalysisId, applicationId, DocumentLanguage.EN, DocumentType.USER_MANUAL); }
    public Document(String sourceAnalysisId, String applicationId, DocumentLanguage language) { this(sourceAnalysisId, applicationId, language, DocumentType.USER_MANUAL); }
    public Document(String sourceAnalysisId, String applicationId, DocumentLanguage language, DocumentType type) { this.id = UUID.randomUUID().toString(); this.sourceAnalysisId = sourceAnalysisId; this.applicationId = applicationId; this.title = "Untitled document"; this.status = DocumentStatus.DRAFT; this.language = language; this.type = type; }
    public String getId() { return id; }
    public String getTitle() { return title; }
    public void updateTitle(String title) { this.title = title; }
    public void updateGeneration(DocumentLanguage language, DocumentType type) { this.language = language; this.type = type; }
    public String getApplicationId() { return applicationId; }
    public String getSourceAnalysisId() { return sourceAnalysisId; }
    public DocumentStatus getStatus() { return status; }
    public DocumentLanguage getLanguage() { return language; }
    public DocumentType getType() { return type; }
    public List<DocumentSection> getSections() { return Collections.unmodifiableList(sections); }
    public void addSection(DocumentSection section) { sections.add(section); }
    public void replaceSections(List<DocumentSection> loaded) { sections.clear(); sections.addAll(loaded); }
    public enum DocumentStatus { DRAFT }
    public enum DocumentLanguage { EN, ES }
    public enum DocumentType { USER_MANUAL }
}
