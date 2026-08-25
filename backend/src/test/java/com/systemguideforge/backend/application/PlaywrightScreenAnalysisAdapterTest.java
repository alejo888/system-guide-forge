package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.TargetApplication;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaywrightScreenAnalysisAdapterTest {
    @Test
    void sanitizationPolicyMasksPasswordAndSensitiveFields() {
        String selector = PlaywrightScreenAnalysisAdapter.SENSITIVE_FIELD_SELECTOR;

        assertThat(selector)
                .contains("input[type='password']")
                .contains("[autocomplete='current-password']")
                .contains("[name*='token' i]")
                .contains("[data-sensitive]");
    }

    @Test
    void authenticatesOnlyAfterSameOriginRedirectAwayFromLogin() {
        TargetApplication app = new TargetApplication(
                "p", "app", "http://localhost:8080", "http://localhost:8080/login", "u", "p");

        assertThat(PlaywrightScreenAnalysisAdapter.isAuthenticatedAfterRedirect(
                "http://localhost:8080/dashboard", app)).isTrue();
        assertThat(PlaywrightScreenAnalysisAdapter.isAuthenticatedAfterRedirect(
                "http://localhost:8080/login", app)).isFalse();
        assertThat(PlaywrightScreenAnalysisAdapter.isAuthenticatedAfterRedirect(
                "http://evil.example/dashboard", app)).isFalse();
    }

    @Test
    void exposesExplicitSingleActiveAnalysisLimit() {
        assertThat(AnalysisService.MAX_ACTIVE_ANALYSES).isEqualTo(1);
    }

    @Test
    void failsClosedWhenBrowserCannotStart() {
        ScreenAnalysisAdapter adapter = new PlaywrightScreenAnalysisAdapter(() -> { throw new IllegalStateException("browser unavailable"); });
        TargetApplication app = new TargetApplication("p", "app", "http://localhost", "http://localhost/login", "u", "p");
        assertThatThrownBy(() -> adapter.analyze(app, "user", "password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
    }
}
