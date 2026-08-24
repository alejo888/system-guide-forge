package com.systemguideforge.backend.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaywrightAccessProbeTest {
    @Test
    void doesNotReportAuthenticationWhenBrowserCannotStart() {
        AccessProbe probe = new PlaywrightAccessProbe(() -> {
            throw new IllegalStateException("browser unavailable");
        });

        AccessResult result = probe.test(new AccessRequest(
                "http://localhost:8080", "http://localhost:8080/login", "user", "password"));

        assertThat(result.reachable()).isFalse();
        assertThat(result.authenticated()).isFalse();
    }
}
