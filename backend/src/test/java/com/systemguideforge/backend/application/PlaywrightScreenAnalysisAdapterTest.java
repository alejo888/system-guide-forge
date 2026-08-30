package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.TargetApplication;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaywrightScreenAnalysisAdapterTest {
    @Test
    void sanitizationPolicyMasksPasswordAndSensitiveFields() {
        String selector = PlaywrightScreenAnalysisAdapter.SENSITIVE_FIELD_SELECTOR;
        assertThat(selector).contains("input[type='password']").contains("[autocomplete='current-password']").contains("[name*='token' i]").contains("[data-sensitive]");
    }
    @Test
    void authenticatesOnlyAfterSameOriginRedirectAwayFromLogin() {
        TargetApplication app = new TargetApplication("p", "app", "http://localhost:8080", "http://localhost:8080/login", "u", "p");
        assertThat(PlaywrightScreenAnalysisAdapter.isAuthenticatedAfterRedirect("http://localhost:8080/dashboard", app)).isTrue();
        assertThat(PlaywrightScreenAnalysisAdapter.isAuthenticatedAfterRedirect("http://localhost:8080/login", app)).isFalse();
        assertThat(PlaywrightScreenAnalysisAdapter.isAuthenticatedAfterRedirect("http://evil.example/dashboard", app)).isFalse();
    }
    @Test
    void followsOnlySafeSameOriginLinksAndStripsFragmentsAndQueries() {
        assertThat(PlaywrightScreenAnalysisAdapter.internalSafeUrl("/reports#today", "http://localhost:8080/home", "http://localhost:8080")).isEqualTo("http://localhost:8080/reports");
        assertThat(PlaywrightScreenAnalysisAdapter.internalSafeUrl("http://localhost:8080/reports", "http://localhost:8080/home", "http://localhost:8080")).isEqualTo("http://localhost:8080/reports");
        assertThat(PlaywrightScreenAnalysisAdapter.classifyResolvedAnchor(null, "http://localhost:8080/reports")).isEqualTo(ActionClassification.SAFE);
        assertThat(PlaywrightScreenAnalysisAdapter.internalSafeUrl("javascript:alert(1)", "http://localhost:8080/home", "http://localhost:8080")).isNull();
    }
    @Test
    void rejectsConfiguredLoginRedirectsAsAuthenticatedPageResults() {
        TargetApplication app = new TargetApplication("p", "app", "http://localhost:8080", "http://localhost:8080/login", "u", "p");
        assertThat(PlaywrightScreenAnalysisAdapter.isLoginNavigationResult("http://localhost:8080/login", app)).isTrue();
        assertThat(PlaywrightScreenAnalysisAdapter.isLoginNavigationResult("http://localhost:8080/dashboard", app)).isFalse();
        assertThat(PlaywrightScreenAnalysisAdapter.isSafeNavigationResult("http://localhost:8080/login", app)).isFalse();
    }
    @Test
    void crawlBudgetBoundsPagesAndLinksGlobally() {
        var budget = new PlaywrightScreenAnalysisAdapter.CrawlBudget(2, 3);
        assertThat(budget.takePage()).isTrue();
        assertThat(budget.takePage()).isTrue();
        assertThat(budget.takePage()).isFalse();
        assertThat(budget.takeLink()).isTrue();
        assertThat(budget.takeLink()).isTrue();
        assertThat(budget.takeLink()).isTrue();
        assertThat(budget.takeLink()).isFalse();
    }
    @Test
    void skipsAnchorsExplicitlyClassifiedAsMutatingOrUnknown() {
        assertThat(PlaywrightScreenAnalysisAdapter.classifyAnchor("MUTATING", "/reports")).isEqualTo(ActionClassification.MUTATING);
        assertThat(PlaywrightScreenAnalysisAdapter.classifyAnchor("UNKNOWN", "/reports")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(PlaywrightScreenAnalysisAdapter.classifyAnchor(null, "/reports")).isEqualTo(ActionClassification.SAFE);
    }
    @Test
    void rejectsUnsafeRedirectsAfterNavigation() {
        TargetApplication app = new TargetApplication("p", "app", "http://localhost:8080", "http://localhost:8080/login", "u", "p");
        assertThat(PlaywrightScreenAnalysisAdapter.isSafeNavigationResult("http://localhost:8080/reports", app)).isTrue();
        assertThat(PlaywrightScreenAnalysisAdapter.isSafeNavigationResult("http://evil.example/reports", app)).isFalse();
        assertThat(PlaywrightScreenAnalysisAdapter.isSafeNavigationResult("javascript:alert(1)", app)).isFalse();
    }
    @Test
    void exposesExplicitSingleActiveAnalysisLimit() { assertThat(AnalysisService.MAX_ACTIVE_ANALYSES).isEqualTo(1); }
    @Test
    void failsClosedWhenBrowserCannotStart() {
        ScreenAnalysisAdapter adapter = new PlaywrightScreenAnalysisAdapter(() -> { throw new IllegalStateException("browser unavailable"); });
        TargetApplication app = new TargetApplication("p", "app", "http://localhost", "http://localhost/login", "u", "p");
        assertThatThrownBy(() -> adapter.analyze(app, "user", "password")).isInstanceOf(IllegalStateException.class).hasMessageContaining("unavailable");
    }
}
