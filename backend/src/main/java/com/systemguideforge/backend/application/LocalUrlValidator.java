package com.systemguideforge.backend.application;

import java.net.URI;

public final class LocalUrlValidator {
    private LocalUrlValidator() {}

    public static boolean isLocal(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return false;
            String host = uri.getHost();
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
        } catch (IllegalArgumentException ex) { return false; }
    }

    public static void requireLocal(String value) {
        if (!isLocal(value)) throw new IllegalArgumentException("Only localhost URLs are allowed");
    }
}
