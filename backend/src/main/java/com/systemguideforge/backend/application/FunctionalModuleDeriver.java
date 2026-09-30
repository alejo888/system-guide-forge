package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.Document;
import com.systemguideforge.backend.persistence.Page;
import com.systemguideforge.backend.persistence.PageKind;
import com.systemguideforge.backend.persistence.UIElement;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class FunctionalModuleDeriver {
    // Contains "/", which moduleKey(url) can never produce: a decoded path segment never contains "/" (see moduleKey).
    // This keeps the login module key collision-free against any ordinary page whose first path segment is "login".
    private static final String LOGIN_MODULE_KEY = "sgf/login";

    private static final Pattern ID_SEGMENT = Pattern.compile("[0-9]+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final int MAX_LABEL_LENGTH = 80;

    public List<Module> derive(List<Page> pages) { return derive(pages, List.of()); }

    /** Module names come from the app's own navigation links found in {@code elements}; see {@link #navigationLabels}. */
    public List<Module> derive(List<Page> pages, List<UIElement> elements) {
        Map<String, String> labels = navigationLabels(elements);
        List<Page> loginPages = pages.stream().filter(page -> page.getKind() == PageKind.LOGIN).toList();
        List<Page> otherPages = pages.stream().filter(page -> page.getKind() != PageKind.LOGIN).toList();

        Map<String, List<Page>> grouped = new java.util.TreeMap<>();
        for (Page page : otherPages) {
            grouped.computeIfAbsent(moduleKey(page.getUrl()), ignored -> new ArrayList<>()).add(page);
        }

        List<Module> modules = new ArrayList<>();
        if (!loginPages.isEmpty()) modules.add(toModule(LOGIN_MODULE_KEY, loginPages, labels));
        grouped.forEach((key, groupedPages) -> modules.add(toModule(key, groupedPages, labels)));
        return modules;
    }

    private Module toModule(String key, List<Page> modulePages, Map<String, String> labels) {
        return new Module(key, displayName(key, labels, null), modulePages.stream()
                .sorted(Comparator.comparing(Page::getUrl).thenComparing(Page::getId))
                .map(page -> new ModulePage(page.getId(), page.getAnalysisId(), page.getUrl(), page.getTitle(), page.getHeading(), page.getKind()))
                .toList());
    }

    public String moduleNameFor(String url) {
        return moduleNameFor(url, Map.of(), null);
    }

    /** The app's own label for the module when known, else the URL-derived name; the fixed Home name follows a known language. */
    public String moduleNameFor(String url, Map<String, String> labels, Document.DocumentLanguage language) {
        return displayName(moduleKey(url), labels, language);
    }

    public String loginModuleName(Document.DocumentLanguage language) {
        return displayName(LOGIN_MODULE_KEY, Map.of(), language);
    }

    /**
     * Module key to the accessible name of the links that point at that module's root route (one path segment, e.g. /projects).
     * The most frequent name wins and ties break alphabetically; blank, redacted and overlong names and the home route are ignored.
     */
    public Map<String, String> navigationLabels(List<UIElement> elements) {
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        for (UIElement element : elements) {
            if (!"a".equalsIgnoreCase(element.getKind()) || element.getTargetPath() == null) continue;
            String key = rootRouteKey(element.getTargetPath());
            String name = element.getAccessibleName() == null ? "" : element.getAccessibleName().trim();
            if (key == null || name.isEmpty() || name.length() > MAX_LABEL_LENGTH || name.contains("[redacted]")) continue;
            counts.computeIfAbsent(key, ignored -> new HashMap<>()).merge(name, 1, Integer::sum);
        }
        Map<String, String> labels = new java.util.TreeMap<>();
        counts.forEach((key, byName) -> byName.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .findFirst().ifPresent(best -> labels.put(key, best.getKey())));
        return labels;
    }

    /** The module key when the path is exactly one non-empty segment; null for the home route and deeper routes. */
    private String rootRouteKey(String path) {
        String only = null;
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) continue;
            if (only != null) return null;
            only = segment;
        }
        return only;
    }

    public String moduleKeyFor(String url) {
        return moduleKey(url);
    }

    public String routeFor(String url) {
        String path = URI.create(url).getPath();
        return path == null || path.isBlank() ? "/" : path;
    }

    /** The route with every purely numeric or UUID path segment replaced by {@code {id}}; other segments stay literal. */
    public String routeTemplateFor(String url) {
        String[] segments = routeFor(url).split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            if (ID_SEGMENT.matcher(segments[index]).matches()) segments[index] = "{id}";
        }
        return String.join("/", segments);
    }

    private String moduleKey(String url) {
        String path = URI.create(url).getPath();
        if (path == null || path.isBlank()) return "home";
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) return segment;
        }
        return "home";
    }

    private String displayName(String key, Map<String, String> labels, Document.DocumentLanguage language) {
        boolean spanish = language == Document.DocumentLanguage.ES;
        if (key.equals(LOGIN_MODULE_KEY)) return language == null ? "Login" : spanish ? "Ingreso al sistema" : "Sign in";
        if (key.equals("home")) return spanish ? "Inicio" : "Home";
        if (labels.containsKey(key)) return labels.get(key);
        String[] words = key.replace('-', ' ').replace('_', ' ').toLowerCase(Locale.ROOT).split(" +");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    public record Module(String key, String name, List<ModulePage> pages) {}
    public record ModulePage(String id, String analysisId, String url, String title, String heading, PageKind kind) {}
}
