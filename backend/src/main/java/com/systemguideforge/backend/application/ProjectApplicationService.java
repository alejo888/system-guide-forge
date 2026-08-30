package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.*;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.*;

@Service
public class ProjectApplicationService {
    static final int DEFAULT_CRAWL_DEPTH = 2;
    static final int MAX_CRAWL_DEPTH = 5;
    static final int MAX_EXCLUDED_ROUTES = 50;
    static final int MAX_ROUTE_LENGTH = 200;
    private static final String EXCLUDED_ROUTE_PATTERN = "^/(?:[^/?#]+(?:/[^/?#]+)*)?/?$";
    private final ProjectRepository projects; private final TargetApplicationRepository applications; private final CredentialProtector protector; private final AccessProbe accessProbe;
    public ProjectApplicationService(ProjectRepository projects, TargetApplicationRepository applications, CredentialProtector protector, AccessProbe accessProbe) { this.projects=projects;this.applications=applications;this.protector=protector;this.accessProbe=accessProbe; }
    public Project createProject(String name) { return projects.save(new Project(name)); }
    public Optional<Project> getProject(String id) { return projects.findById(id); }
    public TargetApplication createApplication(String projectId,String name,String baseUrl,String loginUrl,String username,String password) {
        return createApplication(projectId, name, baseUrl, loginUrl, username, password, DEFAULT_CRAWL_DEPTH, List.of());
    }
    public TargetApplication createApplication(String projectId,String name,String baseUrl,String loginUrl,String username,String password, Integer maxCrawlDepth, List<String> excludedRoutes) {
        validateConfiguration(name, baseUrl, loginUrl, username, password, maxCrawlDepth, excludedRoutes);
        if (!projects.existsById(projectId)) throw new IllegalArgumentException("Project not found");
        return applications.save(new TargetApplication(projectId,name,baseUrl,loginUrl,protector.encrypt(username),protector.encrypt(password),effectiveDepth(maxCrawlDepth),normalizeRoutes(excludedRoutes)));
    }
    public Optional<TargetApplication> getApplication(String id) { return applications.findById(id); }
    public TargetApplication updateApplication(String id, String name, String baseUrl, String loginUrl, String username, String password) {
        return updateApplication(id, name, baseUrl, loginUrl, username, password, DEFAULT_CRAWL_DEPTH, List.of());
    }
    public TargetApplication updateApplication(String id, String name, String baseUrl, String loginUrl, String username, String password, Integer maxCrawlDepth, List<String> excludedRoutes) {
        validateConfiguration(name, baseUrl, loginUrl, username, password, maxCrawlDepth, excludedRoutes);
        TargetApplication application = applications.findById(id).orElseThrow();
        application.updateConfiguration(name, baseUrl, loginUrl, protector.encrypt(username), protector.encrypt(password), effectiveDepth(maxCrawlDepth), normalizeRoutes(excludedRoutes));
        return applications.save(application);
    }
    public AccessResult testAccess(String id) {
        TargetApplication app=applications.findById(id).orElseThrow();
        return accessProbe.test(new AccessRequest(app.getBaseUrl(),app.getLoginUrl(),protector.decrypt(app.getUsernameEncrypted()),protector.decrypt(app.getPasswordEncrypted())));
    }
    private void validateConfiguration(String name, String baseUrl, String loginUrl, String username, String password, Integer depth, List<String> routes) {
        if (isBlank(name) || isBlank(baseUrl) || isBlank(loginUrl) || isBlank(username) || isBlank(password)) throw new IllegalArgumentException("Application configuration fields are required");
        LocalUrlValidator.requireLocal(baseUrl); LocalUrlValidator.requireLocal(loginUrl);
        if (depth == null) depth = DEFAULT_CRAWL_DEPTH;
        if (depth < 0 || depth > MAX_CRAWL_DEPTH) throw new IllegalArgumentException("Crawl depth must be between 0 and 5");
        normalizeRoutes(routes);
    }
    private static int effectiveDepth(Integer depth) { return depth == null ? DEFAULT_CRAWL_DEPTH : depth; }
    static List<String> normalizeRoutes(List<String> routes) {
        if (routes == null) return List.of();
        if (routes.size() > MAX_EXCLUDED_ROUTES) throw new IllegalArgumentException("Too many excluded routes");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String route : routes) {
            if (route == null || route.length() > MAX_ROUTE_LENGTH || route.indexOf('%') >= 0 || !route.matches(EXCLUDED_ROUTE_PATTERN)) throw new IllegalArgumentException("Excluded routes must be normalized URL paths");
            try {
                URI uri = URI.create(route);
                if (uri.getScheme() != null || uri.getHost() != null || !route.equals(uri.getPath())) throw new IllegalArgumentException("Excluded routes must be normalized URL paths");
            } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Excluded routes must be normalized URL paths", ex); }
            String value = route.length() > 1 && route.endsWith("/") ? route.substring(0, route.length() - 1) : route;
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }
    private boolean isBlank(String value) { return value == null || value.isBlank(); }
}
