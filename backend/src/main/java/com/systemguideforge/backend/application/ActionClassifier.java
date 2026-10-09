package com.systemguideforge.backend.application;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ActionClassifier {
    // English and Spanish verbs that change server or session state. Spanish infinitives may carry an attached
    // pronoun ("Quitarme", "Eliminarlo"); UNICODE_CHARACTER_CLASS keeps \b correct around accented letters.
    private static final Pattern MUTATING_VERBS = Pattern.compile(
            "\\b(submit|create|delete|send|save|update|remove|add|log ?out|sign ?out"
                    + "|(de)?activate|disable|enable"
                    + "|(crear|eliminar|borrar|enviar|guardar|actualizar|quitar|agregar|añadir"
                    + "|(des)?activar|(des)?habilitar)(me|te|se|lo|la|le|nos|los|las|les)?"
                    + "|cerrar sesi[oó]n)\\b",
            Pattern.UNICODE_CHARACTER_CLASS);

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

    /**
     * Classifies a control from its type and, for pressable controls only, the accessible name a user would read on it.
     * Text-entry fields keep type-only classification: a placeholder such as "Add a comment" does not submit anything.
     */
    public static ActionClassification classifyControl(String tag, String type, String accessibleName) {
        ActionClassification byType = classify(tag, "type", type);
        if (byType != ActionClassification.UNKNOWN || !isPressable(tag, type)) return byType;
        return classify(tag, "aria-label", accessibleName);
    }

    private static boolean isPressable(String tag, String type) {
        String t = normalize(tag);
        String v = normalize(type);
        return "button".equals(t) || ("input".equals(t) && ("button".equals(v) || "reset".equals(v)));
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
                && MUTATING_VERBS.matcher(value).find();
    }

    private static boolean isSensitive(String attribute, String value) {
        return attribute.matches(".*(password|api[-_]?key|secret|token|credential).*" )
                || value.matches(".*(password|api[-_]?key|secret|token|credential).*" );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
