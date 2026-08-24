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
    public void fail(String message){status=AnalysisStatus.FAILED; completedAt=Instant.now(); failureMessage="Analysis failed";}
}
