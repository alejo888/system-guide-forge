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
    private Set<ExcludedRoute> excludedRouteEntities = new LinkedHashSet<>();
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", referencedColumnName = "id", insertable = false, updatable = false)
    // Both collections are Sets: they are join-fetched together by TargetApplicationRepository (open-in-view is off),
    // and two Lists (bags) would fail with MultipleBagFetchException or repeat rows of the joined product.
    private Set<ApprovedOpener> approvedOpenerEntities = new LinkedHashSet<>();

    protected TargetApplication() {}

    public TargetApplication(String projectId, String name, String baseUrl, String loginUrl, String usernameEncrypted, String passwordEncrypted) {
        this(projectId, name, baseUrl, loginUrl, usernameEncrypted, passwordEncrypted, 2, List.of());
    }

    public TargetApplication(String projectId, String name, String baseUrl, String loginUrl, String usernameEncrypted, String passwordEncrypted, int maxCrawlDepth, List<String> excludedRoutes) {
        this(projectId, name, baseUrl, loginUrl, usernameEncrypted, passwordEncrypted, maxCrawlDepth, excludedRoutes, List.of());
    }

    public TargetApplication(String projectId, String name, String baseUrl, String loginUrl, String usernameEncrypted, String passwordEncrypted, int maxCrawlDepth, List<String> excludedRoutes, List<ApprovedOpenerRule> approvedOpeners) {
        this.id=UUID.randomUUID().toString(); this.projectId=projectId; this.name=name; this.baseUrl=baseUrl; this.loginUrl=loginUrl;
        this.usernameEncrypted=usernameEncrypted; this.passwordEncrypted=passwordEncrypted;
        this.maxCrawlDepth = maxCrawlDepth;
        setExcludedRoutes(excludedRoutes);
        setApprovedOpeners(approvedOpeners);
    }

    public void updateConfiguration(String name, String baseUrl, String loginUrl, String usernameEncrypted, String passwordEncrypted) {
        updateConfiguration(name, baseUrl, loginUrl, usernameEncrypted, passwordEncrypted, maxCrawlDepth, getExcludedRoutes(), getApprovedOpeners());
    }
    public void updateConfiguration(String name, String baseUrl, String loginUrl, String usernameEncrypted, String passwordEncrypted, int maxCrawlDepth, List<String> excludedRoutes) {
        updateConfiguration(name, baseUrl, loginUrl, usernameEncrypted, passwordEncrypted, maxCrawlDepth, excludedRoutes, getApprovedOpeners());
    }
    public void updateConfiguration(String name, String baseUrl, String loginUrl, String usernameEncrypted, String passwordEncrypted, int maxCrawlDepth, List<String> excludedRoutes, List<ApprovedOpenerRule> approvedOpeners) {
        this.name = name; this.baseUrl = baseUrl; this.loginUrl = loginUrl;
        this.usernameEncrypted = usernameEncrypted; this.passwordEncrypted = passwordEncrypted;
        this.maxCrawlDepth = maxCrawlDepth;
        setExcludedRoutes(excludedRoutes);
        setApprovedOpeners(approvedOpeners);
    }
    private void setExcludedRoutes(List<String> routes) {
        excludedRouteEntities.clear();
        if (routes != null) for (String route : routes) excludedRouteEntities.add(new ExcludedRoute(id, route));
    }
    /**
     * Keeps the rows of openers that stay approved and only adds the new ones: Hibernate flushes inserts before
     * orphan deletes, so re-inserting an unchanged opener would collide with the unique (application, route, name) key.
     */
    private void setApprovedOpeners(List<ApprovedOpenerRule> openers) {
        List<ApprovedOpenerRule> wanted = openers == null ? List.of() : openers;
        approvedOpenerEntities.removeIf(opener -> !wanted.contains(opener.toRule()));
        Set<ApprovedOpenerRule> kept = new HashSet<>(getApprovedOpeners());
        for (ApprovedOpenerRule rule : wanted) if (kept.add(rule)) approvedOpenerEntities.add(new ApprovedOpener(id, rule));
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
    /** Sorted by route pattern, then by name ignoring case, so create, update and later reads return the same order. */
    public List<ApprovedOpenerRule> getApprovedOpeners() { return approvedOpenerEntities.stream().map(ApprovedOpener::toRule).sorted(OPENER_ORDER).toList(); }
    private static final Comparator<ApprovedOpenerRule> OPENER_ORDER = Comparator.comparing(ApprovedOpenerRule::routePattern)
            .thenComparing(ApprovedOpenerRule::accessibleName, String.CASE_INSENSITIVE_ORDER).thenComparing(ApprovedOpenerRule::accessibleName);
}
