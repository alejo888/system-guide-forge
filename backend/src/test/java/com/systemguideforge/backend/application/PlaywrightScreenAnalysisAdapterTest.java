package com.systemguideforge.backend.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.microsoft.playwright.Playwright;
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
import static org.assertj.core.groups.Tuple.tuple;

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

    @Test
    void capturesLoginRoleLabelsFromAssociatedLabelsAriaLabelPlaceholderAndSubmitText() throws Exception {
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
                    <label for="user-field">Email</label>
                    <input id="user-field" name="username" type="text">
                    <label for="pass-field">Password</label>
                    <input id="pass-field" name="password" type="password">
                    <button type="submit">Sign in</button></form></body></html>
                    """);
        });
        server.createContext("/dashboard", exchange -> respondHtml(exchange, "<!doctype html><html><body><main>Dashboard</main></body></html>"));
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            TargetApplication application = new TargetApplication("project", "app", baseUrl, baseUrl + "/login", "stored-user", "stored-password");

            ScreenAnalysisAdapter.LoginPage loginPage = new PlaywrightScreenAnalysisAdapter()
                    .analyze(application, "browser-user", "browser-password").loginPage();

            assertThat(loginPage).isNotNull();
            assertThat(loginPage.usernameLabel()).isEqualTo("Email");
            // The associated <label> text is "Password"; safe() redacts it here. DocumentService turns "[redacted]"
            // into generic wording rather than printing it, so the redaction itself is fine to persist.
            assertThat(loginPage.passwordLabel()).isEqualTo("[redacted]");
            assertThat(loginPage.submitLabel()).isEqualTo("Sign in");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesRenderedButtonsAsMutatingFromTheirVisibleLabel() throws Exception {
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
                <!doctype html><html><body><main>
                <button type="button">Crear tarea</button>
                <button type="button">Cerrar sesión</button>
                <button type="button" aria-label="Delete project">×</button>
                <button type="button">Abrir menú</button>
                <button type="button" name="action">Eliminar</button>
                <input type="button" name="op" value="Guardar cambios">
                </main></body></html>
                """));
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            TargetApplication application = new TargetApplication("project", "app", baseUrl, baseUrl + "/login", "stored-user", "stored-password");

            List<ScreenAnalysisAdapter.DetectedElement> elements = new PlaywrightScreenAnalysisAdapter()
                    .analyze(application, "browser-user", "browser-password").elements();

            assertThat(elements).filteredOn(element -> "button".equals(element.kind()))
                    .extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName, ScreenAnalysisAdapter.DetectedElement::classification)
                    .containsExactly(
                            tuple("Crear tarea", ActionClassification.MUTATING),
                            tuple("Cerrar sesión", ActionClassification.MUTATING),
                            tuple("Delete project", ActionClassification.MUTATING),
                            tuple("Abrir menú", ActionClassification.UNKNOWN),
                            // The machine name is not what the user reads; classification still uses the visible label.
                            tuple("Eliminar", ActionClassification.MUTATING));
            assertThat(elements).filteredOn(element -> "input".equals(element.kind()))
                    .extracting(ScreenAnalysisAdapter.DetectedElement::classification)
                    .containsExactly(ActionClassification.MUTATING);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void continuesAnalysisWithoutALoginPageWhenThePreLoginCaptureFailsAndLogsASanitizedWarning() throws Exception {
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
        server.createContext("/dashboard", exchange -> respondHtml(exchange, "<!doctype html><html><body><main>Dashboard</main></body></html>"));
        server.start();
        Logger logger = (Logger) LoggerFactory.getLogger(PlaywrightScreenAnalysisAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            TargetApplication application = new TargetApplication("project", "app", baseUrl, baseUrl + "/login", "stored-user", "stored-password");
            PlaywrightScreenAnalysisAdapter adapter = new PlaywrightScreenAnalysisAdapter(Playwright::create,
                    (page, app) -> { throw new IllegalStateException("simulated pre-login capture failure"); });

            ScreenAnalysisAdapter.ScreenAnalysisResult result = adapter.analyze(application, "browser-user", "browser-password");

            assertThat(result.loginPage()).isNull();
            assertThat(result.url()).isEqualTo(baseUrl + "/dashboard");
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("Login page capture failed")
                        .contains("java.lang.IllegalStateException")
                        .doesNotContain("browser-user")
                        .doesNotContain("browser-password");
            });
        } finally {
            logger.detachAppender(appender);
            server.stop(0);
        }
    }

    @Test
    void capturesFirstVisibleH1AsHeadingAndKeepsTheRawTitle() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard("""
                <!doctype html><html><head><title>App</title></head><body><main>
                <h1 style="display:none">Hidden heading</h1><h1>  Project
                   board </h1><h1>Second</h1><a href="#x">Link</a></main></body></html>
                """, null);

        assertThat(result.heading()).isEqualTo("Project board");
        assertThat(result.title()).isEqualTo("App");
        assertThat(result.loginPage().heading()).isEqualTo("Sign in page");
    }

    @Test
    void fallsBackToUsefulVisibleH2ButPrefersUsefulH1RegardlessOfDomOrder() throws Exception {
        assertThat(analyzeDashboard("<!doctype html><html><body><main><h2>Section</h2><a href=\"#x\">Link</a></main></body></html>", null).heading()).isEqualTo("Section");
        assertThat(analyzeDashboard("<!doctype html><html><body><main><h2>Earlier</h2><h1>Primary</h1><a href=\"#x\">Link</a></main></body></html>", null).heading()).isEqualTo("Primary");
        assertThat(analyzeDashboard("<!doctype html><html><body><main><h1> </h1><h1>Useful</h1><h2>Secondary</h2><a href=\"#x\">Link</a></main></body></html>", null).heading()).isEqualTo("Useful");
    }

    @Test
    void skipsBlankAndHiddenHeadingsAndIgnoresHiddenDescendantText() throws Exception {
        assertThat(analyzeDashboard("<!doctype html><html><body><main><h1 hidden>Nope</h1><h1> </h1><h2 style=\"display:none\">Nope</h2><h2>Visible <span hidden>secret</span> section</h2><a href=\"#x\">Link</a></main></body></html>", null).heading()).isEqualTo("Visible section");
        assertThat(analyzeDashboard("<!doctype html><html><head><title>App</title></head><body><main><h1 hidden>Nope</h1><h1> </h1><h2 hidden>Nope</h2><h2> </h2><a href=\"#x\">Link</a></main></body></html>", null).heading()).isNull();
    }

    @Test
    void normalizesRedactsAndTruncatesH2ByCodePoints() throws Exception {
        assertThat(analyzeDashboard("<!doctype html><html><body><main><h2>  Reset   password </h2><a href=\"#x\">Link</a></main></body></html>", null).heading()).isEqualTo("Reset [redacted]");
        String heading = analyzeDashboard("<!doctype html><html><body><main><h2>" + "a".repeat(254) + "😀more</h2><a href=\"#x\">Link</a></main></body></html>", null).heading();
        assertThat(heading).isEqualTo("a".repeat(254) + "😀");
        assertThat(heading.codePointCount(0, heading.length())).isEqualTo(255);
    }

    @Test
    void redactsSensitiveWordsAndCapsTheHeadingLength() throws Exception {
        assertThat(analyzeDashboard("<!doctype html><html><body><main><h1>Reset password</h1><a href=\"#x\">Link</a></main></body></html>", null).heading())
                .isEqualTo("Reset [redacted]");
        assertThat(analyzeDashboard("<!doctype html><html><body><main><h1>" + "a".repeat(300) + "</h1><a href=\"#x\">Link</a></main></body></html>", null).heading())
                .hasSize(255);
    }

    @Test
    void propagatesFallbackH2OnLoginAndDiscoveredPages() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><main><h2>Home section</h2><a href=\"/next\">Next</a></main></body></html>",
                "<!doctype html><html><body><main><h1 hidden>Hidden</h1><h2>Next section</h2><a href=\"/dashboard\">Back</a></main></body></html>",
                "<h2>Sign in section</h2>");
        assertThat(result.heading()).isEqualTo("Home section");
        assertThat(result.loginPage().heading()).isEqualTo("Sign in section");
        assertThat(result.discoveredPages()).extracting(ScreenAnalysisAdapter.DiscoveredPage::heading).containsExactly("Next section");
    }

    @Test
    void capturesHeadingOnDiscoveredPages() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard("<!doctype html><html><body><main><h1>Home</h1><a href=\"/next\">Next</a></main></body></html>",
                "<!doctype html><html><head><title>App</title></head><body><main><h1>Next page</h1><a href=\"/dashboard\">Back</a></main></body></html>");

        assertThat(result.discoveredPages()).extracting(ScreenAnalysisAdapter.DiscoveredPage::heading).containsExactly("Next page");
        assertThat(result.discoveredPages()).extracting(ScreenAnalysisAdapter.DiscoveredPage::title).containsExactly("App");
    }

    @Test
    void keepsHeadingsWhenSharedNavigationIsIncludedOnlyOnce() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><main><h1>Home</h1><a href=\"/next\">Menu</a></main></body></html>",
                "<!doctype html><html><head><title>App</title></head><body><main><h1>Next page</h1><a href=\"/dashboard\">Menu</a><a href=\"#own\">Own link</a></main></body></html>");

        // Regression guard: the shared "Menu" link is kept only on the start page, so the rebuild branch ran.
        assertThat(result.elements()).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName).contains("Menu");
        assertThat(result.discoveredPages()).singleElement().satisfies(page ->
                assertThat(page.elements()).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName).doesNotContain("Menu").contains("Own link"));
        assertThat(result.heading()).isEqualTo("Home");
        assertThat(result.discoveredPages()).extracting(ScreenAnalysisAdapter.DiscoveredPage::heading).containsExactly("Next page");
    }

    @Test
    void capturesTheSameOriginLinkTargetPathWithoutQueryFragmentOrFragmentOnlyLinks() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><main><h1>Home</h1><a href=\"/next?token=abc#top\">Proyectos</a><a href=\"#home\">Ancla</a>"
                        + "<a href=\"https://external.example/x\">Externo</a><button>Guardar</button></main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");

        assertThat(result.elements()).filteredOn(element -> "Proyectos".equals(element.accessibleName())).extracting(ScreenAnalysisAdapter.DetectedElement::targetPath).containsExactly("/next");
        assertThat(result.elements()).filteredOn(element -> List.of("Ancla", "Externo", "Guardar").contains(element.accessibleName()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::targetPath).containsOnlyNulls();
    }

    @Test
    void flagsLinksInsideNavigationLandmarksOnly() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><header><a href=\"/next\">Cabecera</a></header><nav><a href=\"/next\">Menu</a></nav><div role=\"navigation\"><a href=\"/next\">Rol</a></div>"
                        + "<main><h1>Home</h1><a href=\"/next\">Ver todos</a><button>Guardar</button></main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");

        assertThat(result.elements()).filteredOn(element -> List.of("Cabecera", "Menu", "Rol").contains(element.accessibleName()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::inNavigation).containsExactly(true, true, true);
        assertThat(result.elements()).filteredOn(element -> List.of("Ver todos", "Guardar").contains(element.accessibleName()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::inNavigation).containsExactly(false, false);
    }

    @Test
    void accessibleNameSkipsAriaHiddenIconTextAndSeparatesAdjacentVisibleSpans() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><main><h1>Home</h1><a href=\"#home\" style=\"display:flex\"><span aria-hidden=\"true\">FP</span><span>FlowPilot</span></a>"
                        + "<button style=\"display:flex\"><span>Nuevo</span><span>proyecto</span></button><button>Hel<b>lo</b></button><button>Guardar<span style=\"display:none\">oculto</span></button></main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");

        assertThat(result.elements()).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName).contains("FlowPilot", "Nuevo proyecto", "Hello", "Guardar").doesNotContain("FPFlowPilot", "Nuevoproyecto", "Hel lo", "Guardaroculto", "Guardar oculto");
    }

    @Test
    void recoversHumanLabelsWithoutInventingNamesOrLeakingControlValues() throws Exception {
        var elements = analyzeDashboard("""
                <!doctype html><html><body><main><h1>Form</h1>
                <label>Start date <input type="date" name="startDate" value="2025-01-01"></label>
                <label for="owner">Owner</label><input id="owner" name="ownerField">
                <span id="first">Project</span><span id="second">code</span>
                <input aria-labelledby="first second" aria-label="Wrong" placeholder="Wrong placeholder">
                <input aria-labelledby="missing" aria-label="Fallback aria">
                <input aria-labelledby="missing" placeholder="Hint">
                <input name="machineOnly"><textarea id="direct-textarea" name="machineTextarea">private-fixture-value</textarea>
                <input id="direct-input" value="direct-input-value">
                <input aria-labelledby="direct-textarea" aria-label="Safe fallback">
                <input aria-labelledby="direct-input" placeholder="Input fallback">
                <label>Notes <textarea>nested-private-value</textarea></label>
                <label for="referenced">Reference <input value="associated-private-value"></label><input id="referenced">
                <span id="reference-label">Reference <textarea>reference-private-value</textarea></span>
                <input aria-labelledby="reference-label">
                <button>Save changes</button><a href="#local">View details</a>
                </main></body></html>
                """, null).elements();
        assertThat(elements).filteredOn(e -> "input".equals(e.kind()) || "textarea".equals(e.kind()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName, ScreenAnalysisAdapter.DetectedElement::classification)
                .containsExactly(tuple("Start date", ActionClassification.UNKNOWN), tuple("Owner", ActionClassification.UNKNOWN),
                        tuple("Project code", ActionClassification.UNKNOWN), tuple("Fallback aria", ActionClassification.UNKNOWN),
                        tuple("Hint", ActionClassification.UNKNOWN), tuple(null, ActionClassification.UNKNOWN),
                        tuple(null, ActionClassification.UNKNOWN), tuple("Safe fallback", ActionClassification.UNKNOWN),
                        tuple("Input fallback", ActionClassification.UNKNOWN), tuple(null, ActionClassification.UNKNOWN),
                        tuple("Reference", ActionClassification.UNKNOWN), tuple("Reference", ActionClassification.UNKNOWN),
                        tuple(null, ActionClassification.UNKNOWN), tuple("Notes", ActionClassification.UNKNOWN), tuple(null, ActionClassification.UNKNOWN));
        assertThat(elements).filteredOn(e -> "button".equals(e.kind()) || "a".equals(e.kind()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName).contains("Save changes", "View details");
        assertThat(elements).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName)
                .doesNotContain("nested-private-value", "reference-private-value", "associated-private-value", "private-fixture-value", "direct-input-value");
    }

    @Test
    void humanLabelsExcludeHiddenDescendantText() throws Exception {
        var elements = analyzeDashboard("""
                <!doctype html><html><head><style>.gone{display:none}.ghost{visibility:hidden}</style></head><body><main><h1>Form</h1>
                <label>Due <span class="gone">hidden-display</span><span aria-hidden="true">hidden-aria</span><span class="ghost">hidden-visibility</span><span hidden>hidden-attribute</span><script>var hiddenScript = 1;</script><style>.x{}</style>date <input name="due"></label>
                <span id="hidden-ref" style="display:none">Assignee <span class="gone">hidden-ref-child</span></span>
                <input aria-labelledby="hidden-ref">
                <span id="ghost-ref" style="visibility:hidden">Reviewer</span>
                <input aria-labelledby="ghost-ref">
                </main></body></html>
                """, null).elements();
        assertThat(elements).filteredOn(e -> "input".equals(e.kind()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName)
                .containsExactly("Due date", "Assignee", "Reviewer");
    }

    @Test
    void namesButtonLikeInputsFromTheirValueButNeverOtherInputs() throws Exception {
        var elements = analyzeDashboard("""
                <!doctype html><html><body><main><h1>Form</h1>
                <form><input type="submit" value="Save draft"><input type="button" value="Preview"><input type="reset" value="Clear form">
                <input type="submit" value="Ignored value" aria-label="Publish"><input type="text" value="typed-private-value"></form>
                </main></body></html>
                """, null).elements();
        assertThat(elements).filteredOn(e -> "input".equals(e.kind()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName)
                .containsExactly("Save draft", "Preview", "Clear form", "Publish", null);
    }

    @Test
    void headingEvaluationFailureLeavesHeadingNullAndLogsOnlyExceptionClass() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(PlaywrightScreenAnalysisAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard("""
                    <!doctype html><html><body><main><h2>Fallback</h2><a href="#x">Link</a></main>
                    <script>
                    const originalQuery = document.querySelectorAll.bind(document);
                    document.querySelectorAll = selector => {
                      if (selector === 'h1' || selector === 'h2') throw new Error('private-heading https://example.test/?token=private');
                      return originalQuery(selector);
                    };
                    </script></body></html>
                    """, null);
            assertThat(result.heading()).isNull();
            assertThat(result.title()).isNotNull();
            assertThat(appender.list).filteredOn(event -> event.getFormattedMessage().startsWith("Heading capture failed"))
                    .singleElement().satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).contains("com.microsoft.playwright.PlaywrightException")
                                .doesNotContain("private-heading", "example.test", "token=private");
                        assertThat(event.getThrowableProxy()).isNull();
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static ScreenAnalysisAdapter.ScreenAnalysisResult analyzeDashboard(String dashboardHtml, String nextHtml) throws Exception {
        return analyzeDashboard(dashboardHtml, nextHtml, "<h1>Sign in page</h1>");
    }

    private static ScreenAnalysisAdapter.ScreenAnalysisResult analyzeDashboard(String dashboardHtml, String nextHtml, String loginHeading) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Location", "/dashboard");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            respondHtml(exchange, "<!doctype html><html><head><title>Sign in</title></head><body>" + loginHeading + "<form method=\"post\">"
                    + "<input name=\"username\" type=\"text\"><input name=\"password\" type=\"password\"><button type=\"submit\">Go</button></form></body></html>");
        });
        server.createContext("/dashboard", exchange -> respondHtml(exchange, dashboardHtml));
        if (nextHtml != null) server.createContext("/next", exchange -> respondHtml(exchange, nextHtml));
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            TargetApplication application = new TargetApplication("project", "app", baseUrl, baseUrl + "/login", "stored-user", "stored-password");
            return new PlaywrightScreenAnalysisAdapter().analyze(application, "browser-user", "browser-password");
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
    void dropsOversizedLoginLabelsInsteadOfCuttingThem() {
        String wrappedHelpText = "Email " + "password help text ".repeat(40);
        String redactionAtTheLimit = "x".repeat(PlaywrightScreenAnalysisAdapter.MAX_LOGIN_LABEL_LENGTH - 5) + " password";

        assertThat(PlaywrightScreenAnalysisAdapter.safeLabel(wrappedHelpText)).isNull();
        assertThat(PlaywrightScreenAnalysisAdapter.safeLabel(redactionAtTheLimit)).isNull();
        assertThat(PlaywrightScreenAnalysisAdapter.safeLabel("  Your   password ")).isEqualTo("Your [redacted]");
        assertThat(PlaywrightScreenAnalysisAdapter.MAX_LOGIN_LABEL_LENGTH).isLessThanOrEqualTo(255);
    }

    @Test
    void failsClosedWhenBrowserCannotStart() {
        ScreenAnalysisAdapter adapter = new PlaywrightScreenAnalysisAdapter(() -> { throw new IllegalStateException("browser unavailable"); });
        TargetApplication app = new TargetApplication("p", "app", "http://localhost", "http://localhost/login", "u", "p");
        assertThatThrownBy(() -> adapter.analyze(app, "user", "password")).isInstanceOf(IllegalStateException.class).hasMessageContaining("unavailable");
    }
}
