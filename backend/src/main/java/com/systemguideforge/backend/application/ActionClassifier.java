package com.systemguideforge.backend.application;

public final class ActionClassifier {
    private ActionClassifier() {}

    public static ActionClassification classify(String tag, String attribute, String value) {
        String t = tag == null ? "" : tag.toLowerCase();
        String a = attribute == null ? "" : attribute.toLowerCase();
        String v = value == null ? "" : value.toLowerCase();
        if ("a".equals(t) && "href".equals(a) && !v.isBlank() && !v.startsWith("javascript:")) return ActionClassification.SAFE;
        if (("button".equals(t) || "input".equals(t)) && "submit".equals(v)) return ActionClassification.MUTATING;
        if ("button".equals(t) || "input".equals(t)) return "button".equals(v) ? ActionClassification.UNKNOWN : ActionClassification.MUTATING;
        if ("onclick".equals(a) || "href".equals(a)) return ActionClassification.UNKNOWN;
        return ActionClassification.UNKNOWN;
    }
}
