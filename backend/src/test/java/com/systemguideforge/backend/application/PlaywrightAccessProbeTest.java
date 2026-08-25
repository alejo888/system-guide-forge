package com.systemguideforge.backend.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaywrightAccessProbeTest {
    @Test
    void recognizesAuthenticatedPageEvenWhenItContainsSensitivePasswordInput() {
        AccessRequest request = new AccessRequest(
                "http://localhost:8080", "http://localhost:8080/login", "user", "password");

        assertThat(PlaywrightAccessProbe.isAuthenticatedAfterRedirect(
                "http://localhost:8080/dashboard.html", request)).isTrue();
    }

    @Test
    void recognizesOnlySameOriginRedirectsAwayFromLoginUrl() {
        AccessRequest request = new AccessRequest(
                "http://localhost:8080", "http://localhost:8080/login", "user", "password");

        assertThat(PlaywrightAccessProbe.isSuccessfulRedirect("http://localhost:8080/dashboard.html", request))
                .isTrue();
        assertThat(PlaywrightAccessProbe.isSuccessfulRedirect("http://localhost:8080/login", request))
                .isFalse();
        assertThat(PlaywrightAccessProbe.isSuccessfulRedirect("http://evil.example/dashboard.html", request))
                .isFalse();
        assertThat(PlaywrightAccessProbe.isSuccessfulRedirect(
                "http://localhost:8080/dashboard.html",
                new AccessRequest("http://localhost:8080", "http://evil.example/login", "user", "password")))
                .isFalse();
        assertThat(PlaywrightAccessProbe.isSuccessfulRedirect("not-a-url", request)).isFalse();
    }

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
