package com.systemguideforge.backend.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;

/**
 * Accepts only http(s) URLs on loopback hosts. When {@code sgf.allow-docker-host} is on (the backend runs in a
 * container), the exact host {@code host.docker.internal} is accepted too, so target apps on the Docker host stay
 * reachable; no other host, subdomain or address range is added.
 */
@Component
public class LocalUrlValidator {
    static final String DOCKER_HOST = "host.docker.internal";
    private final boolean allowDockerHost;

    public LocalUrlValidator(@Value("${sgf.allow-docker-host:false}") boolean allowDockerHost) {
        this.allowDockerHost = allowDockerHost;
    }

    public boolean allowsDockerHost() { return allowDockerHost; }

    public boolean isLocal(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return false;
            String host = uri.getHost();
            if (allowDockerHost && DOCKER_HOST.equalsIgnoreCase(host)) return true;
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
        } catch (IllegalArgumentException ex) { return false; }
    }

    public void requireLocal(String value) {
        if (!isLocal(value)) throw new IllegalArgumentException("Only localhost URLs are allowed");
    }
}
