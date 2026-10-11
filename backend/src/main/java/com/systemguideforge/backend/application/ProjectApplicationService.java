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
    static final int MAX_APPROVED_OPENERS = 50;
    static final int MAX_OPENER_NAME_LENGTH = 120;
    private static final String EXCLUDED_ROUTE_PATTERN = "^/(?:[^/?#]+(?:/[^/?#]+)*)?/?$";
    // Same terms as ActionClassifier's sensitive-name check; a sensitive control is never approved as an opener.
    private static final String SENSITIVE_NAME_PATTERN = ".*(password|api[-_ ]?key|secret|token|credential).*";
    private static final String INVALID_OPENER_ROUTE = "Approved opener routes must be URL paths of this application";
    private static final String INVALID_OPENER_NAME = "Approved opener names must be visible, non-sensitive control names of at most 120 characters";
    private final ProjectRepository projects; private final TargetApplicationRepository applications; private final CredentialProtector protector; private final AccessProbe accessProbe;
    public ProjectApplicationService(ProjectRepository projects, TargetApplicationRepository applications, CredentialProtector protector, AccessProbe accessProbe) { this.projects=projects;this.applications=applications;this.protector=protector;this.accessProbe=accessProbe; }
    public Project createProject(String name) { return projects.save(new Project(name)); }
    public Optional<Project> getProject(String id) { return projects.findById(id); }
    public TargetApplication createApplication(String projectId,String name,String baseUrl,String loginUrl,String username,String password) {
        return createApplication(projectId, name, baseUrl, loginUrl, username, password, DEFAULT_CRAWL_DEPTH, List.of());
    }
    public TargetApplication createApplication(String projectId,String name,String baseUrl,String loginUrl,String username,String password, Integer maxCrawlDepth, List<String> excludedRoutes) {
        return createApplication(projectId, name, baseUrl, loginUrl, username, password, maxCrawlDepth, excludedRoutes, List.of());
    }
    public TargetApplication createApplication(String projectId,String name,String baseUrl,String loginUrl,String username,String password, Integer maxCrawlDepth, List<String> excludedRoutes, List<ApprovedOpenerRule> approvedOpeners) {
        validateConfiguration(name, baseUrl, loginUrl, username, password, maxCrawlDepth, excludedRoutes, approvedOpeners);
        if (!projects.existsById(projectId)) throw new IllegalArgumentException("Project not found");
        return applications.save(new TargetApplication(projectId,name,baseUrl,loginUrl,protector.encrypt(username),protector.encrypt(password),effectiveDepth(maxCrawlDepth),normalizeRoutes(excludedRoutes),normalizeApprovedOpeners(approvedOpeners)));
    }
    public List<TargetApplication> listApplications() { return applications.findAllByOrderByNameAscIdAsc(); }
    public Optional<TargetApplication> getApplication(String id) { return applications.findById(id); }
    public TargetApplication updateApplication(String id, String name, String baseUrl, String loginUrl, String username, String password) {
        return updateApplication(id, name, baseUrl, loginUrl, username, password, DEFAULT_CRAWL_DEPTH, List.of());
    }
    public TargetApplication updateApplication(String id, String name, String baseUrl, String loginUrl, String username, String password, Integer maxCrawlDepth, List<String> excludedRoutes) {
        return updateApplication(id, name, baseUrl, loginUrl, username, password, maxCrawlDepth, excludedRoutes, List.of());
    }
    /** A full replacement, like the rest of the configuration: missing ({@code null}) excluded routes or approved openers clear them. */
    public TargetApplication updateApplication(String id, String name, String baseUrl, String loginUrl, String username, String password, Integer maxCrawlDepth, List<String> excludedRoutes, List<ApprovedOpenerRule> approvedOpeners) {
        validateConfiguration(name, baseUrl, loginUrl, username, password, maxCrawlDepth, excludedRoutes, approvedOpeners);
        TargetApplication application = applications.findById(id).orElseThrow();
        application.updateConfiguration(name, baseUrl, loginUrl, protector.encrypt(username), protector.encrypt(password), effectiveDepth(maxCrawlDepth), normalizeRoutes(excludedRoutes), normalizeApprovedOpeners(approvedOpeners));
        return applications.save(application);
    }
    public AccessResult testAccess(String id) {
        TargetApplication app=applications.findById(id).orElseThrow();
        return accessProbe.test(new AccessRequest(app.getBaseUrl(),app.getLoginUrl(),protector.decrypt(app.getUsernameEncrypted()),protector.decrypt(app.getPasswordEncrypted())));
    }
    private void validateConfiguration(String name, String baseUrl, String loginUrl, String username, String password, Integer depth, List<String> routes, List<ApprovedOpenerRule> openers) {
        if (isBlank(name) || isBlank(baseUrl) || isBlank(loginUrl) || isBlank(username) || isBlank(password)) throw new IllegalArgumentException("Application configuration fields are required");
        LocalUrlValidator.requireLocal(baseUrl); LocalUrlValidator.requireLocal(loginUrl);
        if (depth == null) depth = DEFAULT_CRAWL_DEPTH;
        if (depth < 0 || depth > MAX_CRAWL_DEPTH) throw new IllegalArgumentException("Crawl depth must be between 0 and 5");
        normalizeRoutes(routes);
        normalizeApprovedOpeners(openers);
    }
    private static int effectiveDepth(Integer depth) { return depth == null ? DEFAULT_CRAWL_DEPTH : depth; }
    static List<String> normalizeRoutes(List<String> routes) {
        if (routes == null) return List.of();
        if (routes.size() > MAX_EXCLUDED_ROUTES) throw new IllegalArgumentException("Too many excluded routes");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String route : routes) {
            if (!isLocalPath(route)) throw new IllegalArgumentException("Excluded routes must be normalized URL paths");
            String value = route.length() > 1 && route.endsWith("/") ? route.substring(0, route.length() - 1) : route;
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }
    /**
     * RF24: each opener is a same-application path (no scheme, host, query, fragment or escapes) reduced to its RF23
     * route template ({@code /projects/1/board} and {@code /projects/{id}/board} both become the latter) plus the
     * control's trimmed, whitespace-collapsed visible name. Openers the matcher would treat as equal keep the first one.
     */
    static List<ApprovedOpenerRule> normalizeApprovedOpeners(List<ApprovedOpenerRule> openers) {
        if (openers == null) return List.of();
        if (openers.size() > MAX_APPROVED_OPENERS) throw new IllegalArgumentException("Too many approved openers");
        Map<String, ApprovedOpenerRule> normalized = new LinkedHashMap<>();
        for (ApprovedOpenerRule opener : openers) {
            if (opener == null) throw new IllegalArgumentException(INVALID_OPENER_ROUTE);
            // A literal {id} segment stands for any record; "0" is an equivalent id segment that URI parsing accepts.
            String route = opener.routePattern() == null ? null : opener.routePattern().replaceAll("/\\{id}(?=/|$)", "/0");
            if (!isLocalPath(route)) throw new IllegalArgumentException(INVALID_OPENER_ROUTE);
            String name = ApprovedOpenerMatcher.displayName(opener.accessibleName());
            if (name.isEmpty() || name.length() > MAX_OPENER_NAME_LENGTH || name.toLowerCase(Locale.ROOT).matches(SENSITIVE_NAME_PATTERN)) throw new IllegalArgumentException(INVALID_OPENER_NAME);
            ApprovedOpenerRule rule = new ApprovedOpenerRule(ApprovedOpenerMatcher.routeTemplateOf(route), name);
            if (rule.routePattern().length() > MAX_ROUTE_LENGTH) throw new IllegalArgumentException(INVALID_OPENER_ROUTE);
            normalized.putIfAbsent(rule.routePattern() + "\n" + ApprovedOpenerMatcher.nameKey(name), rule);
        }
        return List.copyOf(normalized.values());
    }
    private static boolean isLocalPath(String route) {
        if (route == null || route.length() > MAX_ROUTE_LENGTH || route.indexOf('%') >= 0 || !route.matches(EXCLUDED_ROUTE_PATTERN)) return false;
        try {
            URI uri = URI.create(route);
            return uri.getScheme() == null && uri.getHost() == null && route.equals(uri.getPath());
        } catch (IllegalArgumentException ex) { return false; }
    }
    private boolean isBlank(String value) { return value == null || value.isBlank(); }
}
