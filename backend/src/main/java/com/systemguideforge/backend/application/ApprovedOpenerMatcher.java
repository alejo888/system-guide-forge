package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.ApprovedOpenerRule;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether a control on a page is one of the application's human-approved form openers (RF24). Pure: it only
 * compares the page's route template (RF23 {@code {id}} segments) and the control's visible name; it never presses
 * anything.
 */
public final class ApprovedOpenerMatcher {
    private static final FunctionalModuleDeriver ROUTES = new FunctionalModuleDeriver();
    private final List<ApprovedOpenerRule> openers;

    public ApprovedOpenerMatcher(List<ApprovedOpenerRule> openers) {
        this.openers = openers == null ? List.of() : List.copyOf(openers);
    }

    public boolean matches(String pageUrlOrPath, String accessibleName) {
        String route = routeTemplateOf(pageUrlOrPath);
        String name = nameKey(accessibleName);
        if (route == null || name.isEmpty()) return false;
        return openers.stream().anyMatch(opener -> route.equals(opener.routePattern()) && name.equals(nameKey(opener.accessibleName())));
    }

    /**
     * The RF23 route template of a URL or absolute path, without a trailing slash (except for {@code /}); query and
     * fragment are ignored. {@code null} when the value cannot be parsed.
     */
    static String routeTemplateOf(String urlOrPath) {
        if (urlOrPath == null || urlOrPath.isBlank()) return null;
        try {
            String template = ROUTES.routeTemplateFor(urlOrPath.trim());
            return template.length() > 1 && template.endsWith("/") ? template.substring(0, template.length() - 1) : template;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** The visible name as it is stored: NFC, trimmed, and with inner whitespace runs collapsed to one space. */
    static String displayName(String accessibleName) {
        if (accessibleName == null) return "";
        return Normalizer.normalize(accessibleName, Normalizer.Form.NFC).replaceAll("\\s+", " ").trim();
    }

    /** The comparison key of a visible name: {@link #displayName} in lower case. */
    static String nameKey(String accessibleName) {
        return displayName(accessibleName).toLowerCase(Locale.ROOT);
    }
}
