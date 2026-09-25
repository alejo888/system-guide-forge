package com.systemguideforge.backend.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import com.systemguideforge.backend.persistence.TargetApplication;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaywrightScreenAnalysisAdapterTest {
    @Test
    void waitsForCompleteDocumentWithRenderedContentBeforeExtraction() {
        assertThat(PlaywrightScreenAnalysisAdapter.hasRenderableContent("loading", "Reports", 2, true)).isFalse();
        assertThat(PlaywrightScreenAnalysisAdapter.hasRenderableContent("complete", "", 2, true)).isFalse();
        assertThat(PlaywrightScreenAnalysisAdapter.hasRenderableContent("complete", "Reports", 0, true)).isTrue();
        assertThat(PlaywrightScreenAnalysisAdapter.hasRenderableContent("complete", "Reports", 2, false)).isTrue();
        assertThat(PlaywrightScreenAnalysisAdapter.hasRenderableContent("complete", "Reports", 0, false)).isFalse();
    }

    @Test
    void doesNotTreatLoadingPlaceholderAsRenderedSecondaryPageContent() {
        assertThat(PlaywrightScreenAnalysisAdapter.hasRenderableContent("complete", "Reports Loading report controls...", 0, true, true)).isFalse();
        assertThat(PlaywrightScreenAnalysisAdapter.hasRenderableContent("complete", "Reports Reports are ready.", 2, true, false)).isTrue();
    }

    @Test
    void sanitizationPolicyMasksPasswordAndSensitiveFields() {
        String selector = PlaywrightScreenAnalysisAdapter.SENSITIVE_FIELD_SELECTOR;
        assertThat(selector).contains("input[type='password']").contains("[autocomplete='current-password']").contains("[name*='token' i]").contains("[data-sensitive]");
    }

    @Test
    void sanitizesSensitiveFieldsInBrowserScreenshotWhilePreservingNonSensitiveContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Location", "/dashboard");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            respondHtml(exchange, """
                    <!doctype html><html><body><form method="post">
                    <input name="username" type="text"><input name="password" type="password">
                    <button type="submit">Sign in</button></form></body></html>
                    """);
        });
        server.createContext("/dashboard", exchange -> respondHtml(exchange, """
                <!doctype html><html><head><style>
                body { margin: 0; font-family: sans-serif; }
                #safe-marker { width: 320px; height: 80px; background: rgb(1, 123, 45); color: white; }
                input, [data-sensitive] { display: block; width: 320px; height: 32px; margin-top: 12px; }
                </style></head><body><main><div id="safe-marker">PUBLIC DASHBOARD MARKER</div>
                <input type="password" name="accountPassword" value="password-value">
                <input type="text" name="accessToken" value="token-value">
                <div data-sensitive>private sensitive data</div>
                </main></body></html>
                """));
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            TargetApplication application = new TargetApplication("project", "app", baseUrl, baseUrl + "/login", "stored-user", "stored-password");

            byte[] screenshot = new PlaywrightScreenAnalysisAdapter().analyze(application, "browser-user", "browser-password").sanitizedScreenshot();

            assertThat(screenshot).isNotEmpty();
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(screenshot));
            assertThat(image).isNotNull();
            assertThat(pixelCount(image, 0xFF00FF)).isGreaterThan(100);
            assertThat(pixelCount(image, 0x017B2D)).isGreaterThan(1_000);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void capturesLoginPageWithEmptyMaskedFormBeforeCredentialsAreTyped() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Location", "/dashboard");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            respondHtml(exchange, """
                    <!doctype html><html><head><title>Sign in</title><style>
                    body { margin: 0; font-family: sans-serif; }
                    #username-marker { display: block; width: 320px; height: 80px; }
                    input[name='username']:placeholder-shown ~ #username-marker { background: rgb(1, 123, 45); }
                    input[name='username']:not(:placeholder-shown) ~ #username-marker { background: rgb(255, 0, 0); }
                    input { display: block; width: 320px; height: 32px; margin-top: 12px; }
                    </style></head><body><form method="post">
                    <input name="username" type="text" placeholder="Username">
                    <div id="username-marker"></div>
                    <input name="password" type="password">
                    <button type="submit">Sign in</button></form></body></html>
                    """);
        });
        server.createContext("/dashboard", exchange -> respondHtml(exchange, "<!doctype html><html><body><main>Dashboard</main></body></html>"));
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            TargetApplication application = new TargetApplication("project", "app", baseUrl, baseUrl + "/login", "stored-user", "stored-password");

            ScreenAnalysisAdapter.ScreenAnalysisResult result = new PlaywrightScreenAnalysisAdapter().analyze(application, "browser-user", "browser-password");

            ScreenAnalysisAdapter.LoginPage loginPage = result.loginPage();
            assertThat(loginPage).isNotNull();
            assertThat(loginPage.url()).isEqualTo(baseUrl + "/login");
            assertThat(loginPage.elements()).anySatisfy(element ->
                    assertThat(element.classification()).isEqualTo(ActionClassification.UNKNOWN));

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(loginPage.sanitizedScreenshot()));
            assertThat(image).isNotNull();
            assertThat(pixelCount(image, 0xFF00FF)).isGreaterThan(100);
            assertThat(pixelCount(image, 0x017B2D)).isGreaterThan(1_000);
            assertThat(pixelCount(image, 0xFF0000)).isZero();
        } finally {
            server.stop(0);
        }
    }

    private static void respondHtml(com.sun.net.httpserver.HttpExchange exchange, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static long pixelCount(BufferedImage image, int rgb) {
        long count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == rgb) count++;
            }
        }
        return count;
    }
    @Test
    void identifiesOnlyNamedLinksPresentOnEveryPageAsSharedNavigation() {
        ScreenAnalysisAdapter.DetectedElement home = new ScreenAnalysisAdapter.DetectedElement("a", "a:nth-of-type(1)", "Home", ActionClassification.SAFE);
        ScreenAnalysisAdapter.DetectedElement reports = new ScreenAnalysisAdapter.DetectedElement("a", "a:nth-of-type(2)", "Reports", ActionClassification.SAFE);
        ScreenAnalysisAdapter.DetectedElement pageAction = new ScreenAnalysisAdapter.DetectedElement("button", "button:nth-of-type(1)", "Refresh", ActionClassification.SAFE);

        assertThat(PlaywrightScreenAnalysisAdapter.sharedNavigationKeys(List.of(
                List.of(home, reports, pageAction),
                List.of(new ScreenAnalysisAdapter.DetectedElement("a", "a:nth-of-type(4)", "Home", ActionClassification.SAFE), reports),
                List.of(new ScreenAnalysisAdapter.DetectedElement("a", "a:nth-of-type(2)", "Home", ActionClassification.SAFE), pageAction))))
                .containsExactly(PlaywrightScreenAnalysisAdapter.navigationKey(home));
    }

    @Test
    void removesSharedNavigationFromDiscoveredPagesWhileKeepingUnnamedAndNonLinkElements() {
        ScreenAnalysisAdapter.DetectedElement home = new ScreenAnalysisAdapter.DetectedElement("a", "a:nth-of-type(1)", "Home", ActionClassification.SAFE);
        ScreenAnalysisAdapter.DetectedElement refresh = new ScreenAnalysisAdapter.DetectedElement("button", "button:nth-of-type(1)", "Refresh", ActionClassification.SAFE);
        ScreenAnalysisAdapter.DetectedElement search = new ScreenAnalysisAdapter.DetectedElement("input", "input:nth-of-type(1)", null, ActionClassification.SAFE);
        ScreenAnalysisAdapter.DetectedElement unnamedLink = new ScreenAnalysisAdapter.DetectedElement("a", "a:nth-of-type(2)", null, ActionClassification.SAFE);
        ScreenAnalysisAdapter.ScreenAnalysisResult first = new ScreenAnalysisAdapter.ScreenAnalysisResult(
                "http://localhost/", "Home", List.of(home, refresh), new byte[]{1});
        ScreenAnalysisAdapter.DiscoveredPage projects = new ScreenAnalysisAdapter.DiscoveredPage(
                "http://localhost/projects", "Projects", List.of(home, search, unnamedLink), new byte[]{1}, 1, ActionClassification.SAFE);

        ScreenAnalysisAdapter.ScreenAnalysisResult result =
                PlaywrightScreenAnalysisAdapter.withSharedNavigationIncludedOnce(first, List.of(projects));

        assertThat(result.elements()).containsExactly(home, refresh);
        assertThat(result.discoveredPages()).singleElement()
                .satisfies(page -> assertThat(page.elements()).containsExactly(search, unnamedLink));
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
        assertThat(PlaywrightScreenAnalysisAdapter.internalSafeUrl("/reports/", "http://localhost:8080/home", "http://localhost:8080")).isEqualTo("http://localhost:8080/reports");
        assertThat(PlaywrightScreenAnalysisAdapter.internalSafeUrl("/", "http://localhost:8080/home", "http://localhost:8080")).isEqualTo("http://localhost:8080/");
        assertThat(PlaywrightScreenAnalysisAdapter.internalSafeUrl("/private%2Farea", "http://localhost:8080/home", "http://localhost:8080")).isNull();
        assertThat(PlaywrightScreenAnalysisAdapter.internalSafeUrl("http://localhost:8080/reports", "http://localhost:8080/home", "http://localhost:8080")).isEqualTo("http://localhost:8080/reports");
        assertThat(PlaywrightScreenAnalysisAdapter.classifyResolvedAnchor(null, "http://localhost:8080/reports")).isEqualTo(ActionClassification.SAFE);
        assertThat(PlaywrightScreenAnalysisAdapter.internalSafeUrl("javascript:alert(1)", "http://localhost:8080/home", "http://localhost:8080")).isNull();
    }
    @Test
    void excludesConfiguredRoutesOnlyOnSegmentBoundaries() {
        TargetApplication app = new TargetApplication("p", "app", "http://localhost:8080", "http://localhost:8080/login", "u", "p", 5, java.util.List.of("/admin"));
        assertThat(app.isExcludedPath("http://localhost:8080/admin")).isTrue();
        assertThat(app.isExcludedPath("http://localhost:8080/admin/users")).isTrue();
        assertThat(app.isExcludedPath("http://localhost:8080/administrator")).isFalse();
    }

    @Test
    void usesApplicationCrawlerDepthInsteadOfGlobalDepth() {
        TargetApplication app = new TargetApplication("p", "app", "http://localhost:8080", "http://localhost:8080/login", "u", "p", 5, java.util.List.of());
        assertThat(app.getMaxCrawlDepth()).isEqualTo(5);
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
        TargetApplication app = new TargetApplication("p", "app", "http://localhost:8080", "http://localhost:8080/login", "u", "p", 5, java.util.List.of("/admin"));
        assertThat(PlaywrightScreenAnalysisAdapter.isSafeNavigationResult("http://localhost:8080/reports", app)).isTrue();
        assertThat(PlaywrightScreenAnalysisAdapter.isSafeNavigationResult("http://localhost:8080/admin/users", app)).isFalse();
        assertThat(PlaywrightScreenAnalysisAdapter.isSafeNavigationResult("http://localhost:8080/private%2Farea", app)).isFalse();
        assertThat(PlaywrightScreenAnalysisAdapter.isSafeNavigationResult("http://evil.example/reports", app)).isFalse();
        assertThat(PlaywrightScreenAnalysisAdapter.isSafeNavigationResult("javascript:alert(1)", app)).isFalse();
    }
    @Test
    void exposesExplicitSingleActiveAnalysisLimit() { assertThat(AnalysisService.MAX_ACTIVE_ANALYSES).isEqualTo(1); }
    @Test
    void preservesSafeFailureOperationWithoutExposingSecrets() {
        ScreenAnalysisAdapter adapter = new PlaywrightScreenAnalysisAdapter(() -> {
            throw new IllegalStateException("navigation failed: password=supersecret https://target.test/home?token=secret");
        });
        TargetApplication app = new TargetApplication("p", "app", "http://localhost", "http://localhost/login", "u", "p");
        assertThatThrownBy(() -> adapter.analyze(app, "user", "password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("navigation failed")
                .hasMessageNotContaining("supersecret")
                .hasMessageNotContaining("https://target.test/home?token=secret");
    }
    @Test
    void categorizesBrowserFailuresWithoutEchoingUntrustedDetails() {
        TargetApplication app = new TargetApplication("p", "app", "http://localhost", "http://localhost/login", "u", "p");
        ScreenAnalysisAdapter adapter = new PlaywrightScreenAnalysisAdapter(() -> {
            throw new IllegalStateException("Timeout 10000ms exceeded while navigating to https://target.test/private?token=secret selector #password");
        });
        assertThatThrownBy(() -> adapter.analyze(app, "user", "password"))
                .hasMessageContaining("timeout")
                .hasMessageNotContaining("target.test")
                .hasMessageNotContaining("#password");
    }

    @Test
    void logsFailureTypeAndOriginWithoutUntrustedMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(PlaywrightScreenAnalysisAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            TargetApplication app = new TargetApplication("p", "app", "http://localhost", "http://localhost/login", "u", "p");
            ScreenAnalysisAdapter adapter = new PlaywrightScreenAnalysisAdapter(() -> {
                throw new NullPointerException("https://target.test/private?token=secret");
            });

            assertThatThrownBy(() -> adapter.analyze(app, "user", "password"));

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("Browser operation failed")
                        .contains("java.lang.NullPointerException")
                        .contains("PlaywrightScreenAnalysisAdapterTest")
                        .doesNotContain("target.test")
                        .doesNotContain("secret");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void failsClosedWhenBrowserCannotStart() {
        ScreenAnalysisAdapter adapter = new PlaywrightScreenAnalysisAdapter(() -> { throw new IllegalStateException("browser unavailable"); });
        TargetApplication app = new TargetApplication("p", "app", "http://localhost", "http://localhost/login", "u", "p");
        assertThatThrownBy(() -> adapter.analyze(app, "user", "password")).isInstanceOf(IllegalStateException.class).hasMessageContaining("unavailable");
    }
}
