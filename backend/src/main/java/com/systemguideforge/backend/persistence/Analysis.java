package com.systemguideforge.backend.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="analyses")
public class Analysis {
    @Id private String id;
    private String applicationId;
    @Enumerated(EnumType.STRING) private AnalysisStatus status;
    private Instant startedAt;
    private Instant completedAt;
    @Column(length=2000) private String failureMessage;
    protected Analysis() {}
    public Analysis(String applicationId) { this.id=UUID.randomUUID().toString(); this.applicationId=applicationId; this.status=AnalysisStatus.RUNNING; this.startedAt=Instant.now(); }
    public String getId(){return id;} public String getApplicationId(){return applicationId;} public AnalysisStatus getStatus(){return status;} public Instant getStartedAt(){return startedAt;} public Instant getCompletedAt(){return completedAt;} public String getFailureMessage(){return failureMessage;}
    public void complete(){status=AnalysisStatus.COMPLETED; completedAt=Instant.now();}
    public void fail(String message){status=AnalysisStatus.FAILED; completedAt=Instant.now(); failureMessage=sanitizeFailureMessage(message);}
    public static String sanitizeFailureMessage(String message) {
        if (message == null || message.isBlank()) return "Analysis failed";
        String sanitized = message.replaceAll("(?i)\\b(?:https?|wss?)://\\S+", "[url redacted]")
                .replaceAll("(?i)(password|passwd|token|secret|cookie|authorization|api[-_]?key|header|credential)\\s*[:=]\\s*[^\\s,;]+", "$1=[redacted]")
                .replaceAll("\\s+", " ").trim();
        if (sanitized.length() > 240) sanitized = sanitized.substring(0, 240) + "...";
        return sanitized.isBlank() ? "Analysis failed" : sanitized;
    }
}
