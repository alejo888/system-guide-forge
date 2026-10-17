package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    // ActionClassifier's English sensitive terms plus their Spanish forms (RF24), matched on the NFC lower-case name; a
    // sensitive control is never approved as an opener. A plain "clave" stays allowed (it also means "key field" or
    // "code"); only "clave de API", "clave API" and "clave secreta" are rejected ("secreta" already matches "secret").
    private static final String SENSITIVE_NAME_PATTERN =
            ".*(password|api[-_ ]?key|secret|token|credential|credencial|contrase(?:n|\\x{f1})a|clave (?:de )?api).*";
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
        if (!projects.existsById(projectId)) throw invalid("PROJECT_NOT_FOUND", "Project not found");
        return applications.save(new TargetApplication(projectId,name,baseUrl,loginUrl,protector.encrypt(username),protector.encrypt(password),effectiveDepth(maxCrawlDepth),normalizeRoutes(excludedRoutes),normalizeApprovedOpeners(approvedOpeners)));
    }
    public List<TargetApplication> listApplications() { return applications.findAllByOrderByNameAscIdAsc(); }
    public Optional<TargetApplication> getApplication(String id) { return applications.findById(id); }
    // The shorter overloads call the full one directly, past Spring's proxy, so each needs its own @Transactional:
    // without it findForUpdateById's SELECT ... FOR UPDATE has no transaction to lock the application in.
    @Transactional
    public TargetApplication updateApplication(String id, String name, String baseUrl, String loginUrl, String username, String password) {
        return updateApplication(id, name, baseUrl, loginUrl, username, password, DEFAULT_CRAWL_DEPTH, List.of());
    }
    @Transactional
    public TargetApplication updateApplication(String id, String name, String baseUrl, String loginUrl, String username, String password, Integer maxCrawlDepth, List<String> excludedRoutes) {
        return updateApplication(id, name, baseUrl, loginUrl, username, password, maxCrawlDepth, excludedRoutes, List.of());
    }
    /**
     * A full replacement, like the rest of the configuration: missing ({@code null}) excluded routes or approved openers
     * clear them. It locks the application like {@link #approveOpener}, so an approval made meanwhile is either replaced
     * as a whole or applied on top of this update, never interleaved with it.
     */
    @Transactional
    public TargetApplication updateApplication(String id, String name, String baseUrl, String loginUrl, String username, String password, Integer maxCrawlDepth, List<String> excludedRoutes, List<ApprovedOpenerRule> approvedOpeners) {
        validateConfiguration(name, baseUrl, loginUrl, username, password, maxCrawlDepth, excludedRoutes, approvedOpeners);
        TargetApplication application = applications.findForUpdateById(id).orElseThrow();
        application.updateConfiguration(name, baseUrl, loginUrl, protector.encrypt(username), protector.encrypt(password), effectiveDepth(maxCrawlDepth), normalizeRoutes(excludedRoutes), normalizeApprovedOpeners(approvedOpeners));
        return applications.save(application);
    }
    /**
     * RF24: approves one more opener without touching the credentials or any other field, so it needs no password.
     * The rule is normalized like {@link #normalizeApprovedOpeners}; an opener the matcher already treats as approved
     * is a no-op. Transactional so the new row is added to the loaded openers instead of merging a detached copy over
     * rows another request may have added meanwhile, and the application row stays locked until commit: concurrent
     * approvals run one after the other, so the duplicate and limit checks see the openers the previous one committed
     * (no 51st opener, no case variant of an approved one, and an exact repeat stays a no-op instead of a key conflict).
     */
    @Transactional
    public TargetApplication approveOpener(String id, ApprovedOpenerRule opener) {
        ApprovedOpenerRule rule = normalizeApprovedOpener(opener);
        TargetApplication application = applications.findForUpdateById(id).orElseThrow();
        List<ApprovedOpenerRule> approved = application.getApprovedOpeners();
        if (approved.stream().anyMatch(existing -> openerKey(existing).equals(openerKey(rule)))) return application;
        if (approved.size() >= MAX_APPROVED_OPENERS) throw invalid("TOO_MANY_APPROVED_OPENERS", "Too many approved openers");
        application.approveOpener(rule);
        return applications.save(application);
    }
    public AccessResult testAccess(String id) {
        TargetApplication app=applications.findById(id).orElseThrow();
        return accessProbe.test(new AccessRequest(app.getBaseUrl(),app.getLoginUrl(),protector.decrypt(app.getUsernameEncrypted()),protector.decrypt(app.getPasswordEncrypted())));
    }
    private void validateConfiguration(String name, String baseUrl, String loginUrl, String username, String password, Integer depth, List<String> routes, List<ApprovedOpenerRule> openers) {
        if (isBlank(name) || isBlank(baseUrl) || isBlank(loginUrl) || isBlank(username) || isBlank(password)) throw invalid("APPLICATION_FIELDS_REQUIRED", "Application configuration fields are required");
        LocalUrlValidator.requireLocal(baseUrl); LocalUrlValidator.requireLocal(loginUrl);
        if (depth == null) depth = DEFAULT_CRAWL_DEPTH;
        if (depth < 0 || depth > MAX_CRAWL_DEPTH) throw invalid("INVALID_CRAWL_DEPTH", "Crawl depth must be between 0 and 5");
        normalizeRoutes(routes);
        normalizeApprovedOpeners(openers);
    }
    private static int effectiveDepth(Integer depth) { return depth == null ? DEFAULT_CRAWL_DEPTH : depth; }
    static List<String> normalizeRoutes(List<String> routes) {
        if (routes == null) return List.of();
        if (routes.size() > MAX_EXCLUDED_ROUTES) throw invalid("TOO_MANY_EXCLUDED_ROUTES", "Too many excluded routes");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String route : routes) {
            if (!isLocalPath(route)) throw invalid("INVALID_EXCLUDED_ROUTE", "Excluded routes must be normalized URL paths");
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
        if (openers.size() > MAX_APPROVED_OPENERS) throw invalid("TOO_MANY_APPROVED_OPENERS", "Too many approved openers");
        Map<String, ApprovedOpenerRule> normalized = new LinkedHashMap<>();
        for (ApprovedOpenerRule opener : openers) {
            ApprovedOpenerRule rule = normalizeApprovedOpener(opener);
            normalized.putIfAbsent(openerKey(rule), rule);
        }
        return List.copyOf(normalized.values());
    }
    /** One opener of {@link #normalizeApprovedOpeners}: its route template and display name, or an IllegalArgumentException. */
    static ApprovedOpenerRule normalizeApprovedOpener(ApprovedOpenerRule opener) {
        if (opener == null) throw invalid("INVALID_OPENER_ROUTE", INVALID_OPENER_ROUTE);
        // A literal {id} segment stands for any record; "0" is an equivalent id segment that URI parsing accepts.
        String route = opener.routePattern() == null ? null : opener.routePattern().replaceAll("/\\{id}(?=/|$)", "/0");
        if (!isLocalPath(route)) throw invalid("INVALID_OPENER_ROUTE", INVALID_OPENER_ROUTE);
        String name = ApprovedOpenerMatcher.displayName(opener.accessibleName());
        if (name.isEmpty() || name.length() > MAX_OPENER_NAME_LENGTH || name.toLowerCase(Locale.ROOT).matches(SENSITIVE_NAME_PATTERN)) throw invalid("INVALID_OPENER_NAME", INVALID_OPENER_NAME);
        ApprovedOpenerRule rule = new ApprovedOpenerRule(ApprovedOpenerMatcher.routeTemplateOf(route), name);
        if (rule.routePattern().length() > MAX_ROUTE_LENGTH) throw invalid("INVALID_OPENER_ROUTE", INVALID_OPENER_ROUTE);
        return rule;
    }
    /** Openers with the same key are the same opener for the matcher: same route template, same name ignoring case. */
    private static String openerKey(ApprovedOpenerRule rule) { return rule.routePattern() + "\n" + ApprovedOpenerMatcher.nameKey(rule.accessibleName()); }
    private static boolean isLocalPath(String route) {
        if (route == null || route.length() > MAX_ROUTE_LENGTH || route.indexOf('%') >= 0 || !route.matches(EXCLUDED_ROUTE_PATTERN)) return false;
        try {
            URI uri = URI.create(route);
            return uri.getScheme() == null && uri.getHost() == null && route.equals(uri.getPath());
        } catch (IllegalArgumentException ex) { return false; }
    }
    private boolean isBlank(String value) { return value == null || value.isBlank(); }
    private static InvalidApplicationConfigurationException invalid(String code, String message) { return new InvalidApplicationConfigurationException(code, message); }
    /**
     * A rejected application configuration or opener approval. The English message stays as before; {@code code} is a
     * stable machine identifier (documented in openapi.yaml) that clients map to their own localized text.
     */
    public static class InvalidApplicationConfigurationException extends IllegalArgumentException {
        private final String code;
        public InvalidApplicationConfigurationException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
