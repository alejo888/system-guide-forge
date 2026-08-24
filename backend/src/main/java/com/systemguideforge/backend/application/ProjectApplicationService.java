package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.*;
import org.springframework.stereotype.Service;
import java.util.Optional;

@Service
public class ProjectApplicationService {
    private final ProjectRepository projects; private final TargetApplicationRepository applications; private final CredentialProtector protector; private final AccessProbe accessProbe;
    public ProjectApplicationService(ProjectRepository projects, TargetApplicationRepository applications, CredentialProtector protector, AccessProbe accessProbe) { this.projects=projects;this.applications=applications;this.protector=protector;this.accessProbe=accessProbe; }
    public Project createProject(String name) { return projects.save(new Project(name)); }
    public Optional<Project> getProject(String id) { return projects.findById(id); }
    public TargetApplication createApplication(String projectId,String name,String baseUrl,String loginUrl,String username,String password) {
        LocalUrlValidator.requireLocal(baseUrl); LocalUrlValidator.requireLocal(loginUrl);
        if (!projects.existsById(projectId)) throw new IllegalArgumentException("Project not found");
        return applications.save(new TargetApplication(projectId,name,baseUrl,loginUrl,protector.encrypt(username),protector.encrypt(password)));
    }
    public Optional<TargetApplication> getApplication(String id) { return applications.findById(id); }
    public AccessResult testAccess(String id) {
        TargetApplication app=applications.findById(id).orElseThrow();
        return accessProbe.test(new AccessRequest(app.getBaseUrl(),app.getLoginUrl(),protector.decrypt(app.getUsernameEncrypted()),protector.decrypt(app.getPasswordEncrypted())));
    }
}
