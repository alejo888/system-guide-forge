package com.systemguideforge.backend.application;

import java.util.Locale;

public final class ActionClassifier {
    private ActionClassifier() {}

    public static ActionClassification classify(String tag, String attribute, String value) {
        String t = normalize(tag);
        String a = normalize(attribute);
        String v = normalize(value);
        if (isSensitive(a, v) || ("input".equals(t) && "password".equals(v))) {
            return ActionClassification.UNKNOWN;
        }
        if ("a".equals(t) && "href".equals(a) && isInternalLink(v)) {
            return ActionClassification.SAFE;
        }
        if (("button".equals(t) || "input".equals(t)) && isMutatingValue(a, v)) {
            return ActionClassification.MUTATING;
        }
        if ("onclick".equals(a) || "href".equals(a)) {
            return ActionClassification.UNKNOWN;
        }
        return ActionClassification.UNKNOWN;
    }

    /** Fixture-only metadata; callers must not use it as a global classification rule. */
    public static ActionClassification classifyFixtureMetadata(String metadata) {
        if (metadata == null) return null;
        try {
            return ActionClassification.valueOf(metadata.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean isInternalLink(String value) {
        return !value.isBlank() && !value.startsWith("javascript:") && !value.matches("^[a-z][a-z0-9+.-]*://.*$")
                && !value.startsWith("//");
    }

    private static boolean isMutatingValue(String attribute, String value) {
        if ("submit".equals(value)) return true;
        return ("aria-label".equals(attribute) || "name".equals(attribute) || "value".equals(attribute))
                && value.matches(".*\\b(submit|create|delete|send|save|update|remove)\\b.*");
    }

    private static boolean isSensitive(String attribute, String value) {
        return attribute.matches(".*(password|api[-_]?key|secret|token|credential).*" )
                || value.matches(".*(password|api[-_]?key|secret|token|credential).*" );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
