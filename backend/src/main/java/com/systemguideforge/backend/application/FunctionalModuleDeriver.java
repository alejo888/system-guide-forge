package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.Page;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FunctionalModuleDeriver {
    public List<Module> derive(List<Page> pages) {
        Map<String, List<Page>> grouped = new java.util.TreeMap<>();
        for (Page page : pages) {
            grouped.computeIfAbsent(moduleKey(page.getUrl()), ignored -> new ArrayList<>()).add(page);
        }
        return grouped.entrySet().stream()
                .map(entry -> new Module(entry.getKey(), displayName(entry.getKey()), entry.getValue().stream()
                        .sorted(Comparator.comparing(Page::getUrl).thenComparing(Page::getId))
                        .map(page -> new ModulePage(page.getId(), page.getAnalysisId(), page.getUrl(), page.getTitle()))
                        .toList()))
                .toList();
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
    public record ModulePage(String id, String analysisId, String url, String title) {}
}
