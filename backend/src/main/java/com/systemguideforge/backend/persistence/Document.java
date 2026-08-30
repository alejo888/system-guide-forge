package com.systemguideforge.backend.persistence;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "documents", uniqueConstraints = @UniqueConstraint(name = "uk_documents_source_analysis", columnNames = "source_analysis_id"))
public class Document {
    @Id private String id;
    private String applicationId;
    private String sourceAnalysisId;
    @Enumerated(EnumType.STRING) private DocumentStatus status;
    @Transient private List<DocumentSection> sections = new ArrayList<>();
    protected Document() {}
    public Document(String sourceAnalysisId, String applicationId) { this.id = UUID.randomUUID().toString(); this.sourceAnalysisId = sourceAnalysisId; this.applicationId = applicationId; this.status = DocumentStatus.DRAFT; }
    public String getId() { return id; }
    public String getApplicationId() { return applicationId; }
    public String getSourceAnalysisId() { return sourceAnalysisId; }
    public DocumentStatus getStatus() { return status; }
    public List<DocumentSection> getSections() { return Collections.unmodifiableList(sections); }
    public void addSection(DocumentSection section) { sections.add(section); }
    public void replaceSections(List<DocumentSection> loaded) { sections.clear(); sections.addAll(loaded); }
    public enum DocumentStatus { DRAFT }
}
