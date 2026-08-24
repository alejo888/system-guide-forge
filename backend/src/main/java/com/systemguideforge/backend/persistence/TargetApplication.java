package com.systemguideforge.backend.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity @Table(name="target_applications")
public class TargetApplication {
    @Id private String id;
    private String projectId;
    private String name;
    private String baseUrl;
    private String loginUrl;
    private String usernameEncrypted;
    private String passwordEncrypted;
    protected TargetApplication() {}
    public TargetApplication(String projectId, String name, String baseUrl, String loginUrl, String usernameEncrypted, String passwordEncrypted) {
        this.id=UUID.randomUUID().toString(); this.projectId=projectId; this.name=name; this.baseUrl=baseUrl; this.loginUrl=loginUrl; this.usernameEncrypted=usernameEncrypted; this.passwordEncrypted=passwordEncrypted;
    }
    public String getId(){return id;} public String getProjectId(){return projectId;} public String getName(){return name;} public String getBaseUrl(){return baseUrl;} public String getLoginUrl(){return loginUrl;} public String getUsernameEncrypted(){return usernameEncrypted;} public String getPasswordEncrypted(){return passwordEncrypted;}
}
