package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.Page;
import com.systemguideforge.backend.persistence.PageKind;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FunctionalModuleDeriver {
    // Contains "/", which moduleKey(url) can never produce: a decoded path segment never contains "/" (see moduleKey).
    // This keeps the login module key collision-free against any ordinary page whose first path segment is "login".
    private static final String LOGIN_MODULE_KEY = "sgf/login";

    public List<Module> derive(List<Page> pages) {
        List<Page> loginPages = pages.stream().filter(page -> page.getKind() == PageKind.LOGIN).toList();
        List<Page> otherPages = pages.stream().filter(page -> page.getKind() != PageKind.LOGIN).toList();

        Map<String, List<Page>> grouped = new java.util.TreeMap<>();
        for (Page page : otherPages) {
            grouped.computeIfAbsent(moduleKey(page.getUrl()), ignored -> new ArrayList<>()).add(page);
        }

        List<Module> modules = new ArrayList<>();
        if (!loginPages.isEmpty()) modules.add(toModule(LOGIN_MODULE_KEY, loginPages));
        grouped.forEach((key, groupedPages) -> modules.add(toModule(key, groupedPages)));
        return modules;
    }

    private Module toModule(String key, List<Page> modulePages) {
        return new Module(key, displayName(key), modulePages.stream()
                .sorted(Comparator.comparing(Page::getUrl).thenComparing(Page::getId))
                .map(page -> new ModulePage(page.getId(), page.getAnalysisId(), page.getUrl(), page.getTitle(), page.getKind()))
                .toList());
    }

    public String moduleNameFor(String url) {
        return displayName(moduleKey(url));
    }

    public String routeFor(String url) {
        String path = URI.create(url).getPath();
        return path == null || path.isBlank() ? "/" : path;
    }

    private String moduleKey(String url) {
        String path = URI.create(url).getPath();
        if (path == null || path.isBlank()) return "home";
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) return segment;
        }
        return "home";
    }

    private String displayName(String key) {
        if (key.equals(LOGIN_MODULE_KEY)) return "Login";
        if (key.equals("home")) return "Home";
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
    public record ModulePage(String id, String analysisId, String url, String title, PageKind kind) {}
}
