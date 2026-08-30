package com.systemguideforge.backend.persistence;

import jakarta.persistence.*;
import java.util.*;

@Entity @Table(name="target_applications")
public class TargetApplication {
    @Id private String id;
    private String projectId;
    private String name;
    private String baseUrl;
    private String loginUrl;
    private String usernameEncrypted;
    private String passwordEncrypted;
    @Column(nullable = false) private int maxCrawlDepth = 2;
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", referencedColumnName = "id", insertable = false, updatable = false)
    @OrderBy("path ASC")
    private List<ExcludedRoute> excludedRouteEntities = new ArrayList<>();

    protected TargetApplication() {}

    public TargetApplication(String projectId, String name, String baseUrl, String loginUrl, String usernameEncrypted, String passwordEncrypted) {
        this(projectId, name, baseUrl, loginUrl, usernameEncrypted, passwordEncrypted, 2, List.of());
    }

    public TargetApplication(String projectId, String name, String baseUrl, String loginUrl, String usernameEncrypted, String passwordEncrypted, int maxCrawlDepth, List<String> excludedRoutes) {
        this.id=UUID.randomUUID().toString(); this.projectId=projectId; this.name=name; this.baseUrl=baseUrl; this.loginUrl=loginUrl;
        this.usernameEncrypted=usernameEncrypted; this.passwordEncrypted=passwordEncrypted;
        this.maxCrawlDepth = maxCrawlDepth;
        setExcludedRoutes(excludedRoutes);
    }

    public void updateConfiguration(String name, String baseUrl, String loginUrl, String usernameEncrypted, String passwordEncrypted) {
        updateConfiguration(name, baseUrl, loginUrl, usernameEncrypted, passwordEncrypted, maxCrawlDepth, getExcludedRoutes());
    }
    public void updateConfiguration(String name, String baseUrl, String loginUrl, String usernameEncrypted, String passwordEncrypted, int maxCrawlDepth, List<String> excludedRoutes) {
        this.name = name; this.baseUrl = baseUrl; this.loginUrl = loginUrl;
        this.usernameEncrypted = usernameEncrypted; this.passwordEncrypted = passwordEncrypted;
        this.maxCrawlDepth = maxCrawlDepth;
        setExcludedRoutes(excludedRoutes);
    }
    private void setExcludedRoutes(List<String> routes) {
        excludedRouteEntities.clear();
        if (routes != null) for (String route : routes) excludedRouteEntities.add(new ExcludedRoute(id, route));
    }
    public boolean isExcludedPath(String urlPath) {
        String path;
        try { path = normalizePath(java.net.URI.create(urlPath).getPath()); }
        catch (RuntimeException ex) { return true; }

        return getExcludedRoutes().stream().anyMatch(route -> route.equals("/") || path.equals(route) || path.startsWith(route + "/"));
    }
    private static String normalizePath(String path) { return path == null || path.isBlank() ? "/" : path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path; }
    public String getId(){return id;} public String getProjectId(){return projectId;} public String getName(){return name;} public String getBaseUrl(){return baseUrl;} public String getLoginUrl(){return loginUrl;} public String getUsernameEncrypted(){return usernameEncrypted;} public String getPasswordEncrypted(){return passwordEncrypted;}
    public int getMaxCrawlDepth() { return maxCrawlDepth; }
    public List<String> getExcludedRoutes() { return excludedRouteEntities.stream().map(ExcludedRoute::getPath).toList(); }
}
