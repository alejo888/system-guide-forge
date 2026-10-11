package com.systemguideforge.backend.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.sun.net.httpserver.HttpServer;
import com.systemguideforge.backend.persistence.ApprovedOpenerRule;
import com.systemguideforge.backend.persistence.TargetApplication;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
    void capturesTheNormalizedInputTypeButNeverTheFieldValue() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><main><h1>Home</h1>"
                        + "<label>Recordarme<input type=\"CheckBox\" checked></label><label>Plan anual<input type=\"radio\" name=\"plan\"></label>"
                        + "<label>Nombre<input value=\"Ana\"></label><label>Correo<input type=\"email\" value=\"ana@example.test\"></label>"
                        + "<label>Raro<input type=\"not a type!\"></label><label>Notas<textarea>Borrador</textarea></label><button>Guardar</button></main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");

        assertThat(result.elements()).filteredOn(element -> List.of("Recordarme", "Plan anual", "Nombre", "Correo", "Raro", "Notas", "Guardar").contains(element.accessibleName()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName, ScreenAnalysisAdapter.DetectedElement::controlType)
                .containsExactlyInAnyOrder(tuple("Recordarme", "checkbox"), tuple("Plan anual", "radio"), tuple("Nombre", "text"), tuple("Correo", "email"),
                        tuple("Raro", "text"), tuple("Notas", null), tuple("Guardar", null));
        assertThat(result.elements()).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName)
                .doesNotContain("Ana", "ana@example.test", "Borrador");
    }

    @Test
    void capturesSelectsAsUnknownFormFieldsNamedOnlyByTheirLabelNeverByTheirOptions() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><main><h1>Miembros</h1>"
                        + "<label for=\"user\">Usuario</label><select id=\"user\"><option>Ana Pérez</option><option>Beto Ruiz</option></select>"
                        + "<select aria-label=\"Rol\"><option>Admin</option><option selected>Miembro</option></select>"
                        + "<label>Estado<select name=\"status\"><option selected>Clave 1234</option></select></label>"
                        + "<select aria-label=\"Eliminar usuario\"><option>Sí</option></select>"
                        + "<select><option selected>Token privado</option><option>Eliminar</option></select></main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");

        assertThat(result.elements()).filteredOn(element -> "select".equals(element.kind()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName, ScreenAnalysisAdapter.DetectedElement::controlType,
                        ScreenAnalysisAdapter.DetectedElement::classification, ScreenAnalysisAdapter.DetectedElement::inNavigation)
                .containsExactly(tuple("Usuario", null, ActionClassification.UNKNOWN, false), tuple("Rol", null, ActionClassification.UNKNOWN, false),
                        tuple("Estado", null, ActionClassification.UNKNOWN, false), tuple("Eliminar usuario", null, ActionClassification.UNKNOWN, false),
                        tuple(null, null, ActionClassification.UNKNOWN, false));
        assertThat(result.elements()).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName).filteredOn(java.util.Objects::nonNull)
                .noneMatch(name -> name.contains("Ana") || name.contains("Beto") || name.contains("Admin") || name.contains("Miembro")
                        || name.contains("Clave") || name.contains("privado") || name.contains("[redacted]"));
    }

    @Test
    void capturesTabRoleButtonsAsSafeTabControlsAndKeepsMutatingTabsMutating() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><main><h1>Tablero</h1>"
                        + "<div role=\"tablist\"><button role=\"tab\" aria-selected=\"true\">Por hacer</button><button role=\"tab\">En progreso</button>"
                        + "<button role=\"tab\">Crear tablero</button></div><button>Filtros</button></main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");

        assertThat(result.elements()).filteredOn(element -> List.of("Por hacer", "En progreso", "Crear tablero", "Filtros").contains(element.accessibleName()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName, ScreenAnalysisAdapter.DetectedElement::controlType,
                        ScreenAnalysisAdapter.DetectedElement::classification)
                .containsExactlyInAnyOrder(tuple("Por hacer", "tab", ActionClassification.SAFE), tuple("En progreso", "tab", ActionClassification.SAFE),
                        tuple("Crear tablero", "tab", ActionClassification.MUTATING), tuple("Filtros", null, ActionClassification.UNKNOWN));
    }

    @Test
    void capturesRepeatedDataItemButtonsAsItemsWithoutClassifyingTheirDataNames() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><main><h1>Tablero</h1><button type=\"button\">Crear tarea</button>"
                        + "<section class=\"board-column\"><h3>Por hacer</h3><div class=\"board-column-list\">"
                        + "<fp-card class=\"board-card cdk-drag\"><button type=\"button\" class=\"card-title\"><span>Crear notificaciones</span></button><p>Alta</p></fp-card>"
                        + "<fp-card class=\"board-card cdk-drag cdk-drag-dragging\"><button type=\"button\" class=\"card-title\"><span>Revisar informe</span></button><p>Baja</p></fp-card>"
                        + "</div></section>"
                        + "<section class=\"board-column\"><h3>En progreso</h3><div class=\"board-column-list\">"
                        + "<fp-card class=\"board-card cdk-drag\"><button type=\"button\" class=\"card-title\"><span>Eliminar duplicados</span></button><p>Media</p></fp-card>"
                        + "</div></section>"
                        + "<section class=\"board-column\"><h3>Hecho</h3><div class=\"board-column-list\">"
                        + "<fp-card class=\"board-card cdk-drag\"><button type=\"button\" class=\"card-title\"><span>Migrar datos</span></button><p>Alta</p></fp-card>"
                        + "<fp-card class=\"board-card cdk-drag\"><button type=\"button\" class=\"card-title\"><span>Documentar API</span></button><p>Baja</p></fp-card>"
                        + "<fp-card class=\"board-card cdk-drag\"><button type=\"button\" class=\"card-title\"><span>Preparar demo</span></button><p>Baja</p></fp-card>"
                        + "</div></section></main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");

        assertThat(result.elements()).filteredOn(element -> "button".equals(element.kind()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName, ScreenAnalysisAdapter.DetectedElement::controlType,
                        ScreenAnalysisAdapter.DetectedElement::classification)
                .containsExactlyInAnyOrder(tuple("Crear tarea", null, ActionClassification.MUTATING),
                        tuple("Crear notificaciones", "item", ActionClassification.UNKNOWN), tuple("Revisar informe", "item", ActionClassification.UNKNOWN),
                        tuple("Eliminar duplicados", "item", ActionClassification.UNKNOWN), tuple("Migrar datos", "item", ActionClassification.UNKNOWN),
                        tuple("Documentar API", "item", ActionClassification.UNKNOWN), tuple("Preparar demo", "item", ActionClassification.UNKNOWN));
    }

    @Test
    void keepsPerRowActionsWithIdenticalNamesAndLoneCardsAsOrdinaryButtons() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult rows = analyzeDashboard(
                "<!doctype html><html><body><main><h1>Usuarios</h1><table><tbody>"
                        + "<tr><td>Ana</td><td><button type=\"button\">Desactivar</button></td></tr>"
                        + "<tr><td>Luis</td><td><button type=\"button\">Desactivar</button></td></tr>"
                        + "<tr><td>Eva</td><td><button type=\"button\">Desactivar</button></td></tr>"
                        + "</tbody></table></main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");
        ScreenAnalysisAdapter.ScreenAnalysisResult loneCard = analyzeDashboard(
                "<!doctype html><html><body><main><h1>Tablero</h1><section class=\"board-column\"><div class=\"board-column-list\">"
                        + "<fp-card class=\"board-card\"><button type=\"button\" class=\"card-title\">Crear notificaciones</button></fp-card>"
                        + "</div></section></main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");

        assertThat(rows.elements()).filteredOn(element -> "button".equals(element.kind()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::controlType, ScreenAnalysisAdapter.DetectedElement::classification)
                .containsExactly(tuple(null, ActionClassification.MUTATING), tuple(null, ActionClassification.MUTATING), tuple(null, ActionClassification.MUTATING));
        assertThat(loneCard.elements()).filteredOn(element -> "button".equals(element.kind()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::controlType, ScreenAnalysisAdapter.DetectedElement::classification)
                .containsExactly(tuple(null, ActionClassification.MUTATING));
    }

    @Test
    void neverTreatsNavigationTabOrSubmitButtonsAsDataItems() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><nav><ul><li><button type=\"button\">Inicio</button></li><li><button type=\"button\">Proyectos</button></li></ul></nav>"
                        + "<main><h1>Panel</h1><div role=\"tablist\"><span><button role=\"tab\">Resumen</button></span><span><button role=\"tab\">Detalle</button></span></div>"
                        + "<ul><li><form><button type=\"submit\">Aprobar Ana</button></form></li><li><form><button type=\"submit\">Aprobar Luis</button></form></li></ul>"
                        + "</main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");

        assertThat(result.elements()).filteredOn(element -> "button".equals(element.kind()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::controlType).doesNotContain("item");
    }

    @Test
    void capturesTitleOnlyCardsAsItemsWhenTheirStructureCarriesContentElsewhere() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><main><h1>Tablero</h1>"
                        + "<section class=\"board-column\"><h3>Por hacer</h3><div class=\"board-column-list\">"
                        + "<fp-card class=\"board-card\"><button type=\"button\" class=\"card-title\"><span>Disenar pantalla de onboarding</span></button></fp-card>"
                        + "<fp-card class=\"board-card\"><button type=\"button\" class=\"card-title\"><span>Crear backup</span></button><span class=\"assignee\">Ana</span></fp-card>"
                        + "</div></section>"
                        + "<section class=\"board-column\"><h3>Hecho</h3><div class=\"board-column-list\">"
                        + "<fp-card class=\"board-card\"><button type=\"button\" class=\"card-title\"><span>Investigar soporte offline-first</span></button></fp-card>"
                        + "</div></section></main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");

        assertThat(result.elements()).filteredOn(element -> "button".equals(element.kind()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName, ScreenAnalysisAdapter.DetectedElement::controlType)
                .containsExactlyInAnyOrder(tuple("Disenar pantalla de onboarding", "item"), tuple("Crear backup", "item"),
                        tuple("Investigar soporte offline-first", "item"));
    }

    @Test
    void keepsAnUnrelatedLoneButtonWithTheSameTagPathAsAnOrdinaryButton() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><main><h1>Proyecto</h1><section class=\"board\"><div class=\"board-column-list\">"
                        + "<div class=\"card\"><button type=\"button\">Revisar informe</button><p>Ana</p></div>"
                        + "<div class=\"card\"><button type=\"button\">Migrar datos</button><p>Luis</p></div>"
                        + "</div></section>"
                        + "<div class=\"settings\"><div class=\"danger-zone\"><button type=\"button\">Eliminar proyecto</button><p>No se puede deshacer</p></div></div>"
                        + "</main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");

        assertThat(result.elements()).filteredOn(element -> "button".equals(element.kind()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName, ScreenAnalysisAdapter.DetectedElement::controlType,
                        ScreenAnalysisAdapter.DetectedElement::classification)
                .containsExactlyInAnyOrder(tuple("Revisar informe", "item", ActionClassification.UNKNOWN),
                        tuple("Migrar datos", "item", ActionClassification.UNKNOWN),
                        tuple("Eliminar proyecto", null, ActionClassification.MUTATING));
    }

    @Test
    void keepsPerRowActionsNamedAfterTheirRecordAsOrdinaryButtons() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><main><h1>Usuarios</h1><table><tbody>"
                        + "<tr><td>Juan</td><td><button type=\"button\">Eliminar Juan</button></td></tr>"
                        + "<tr><td>Ana</td><td><button type=\"button\">Eliminar Ana</button></td></tr>"
                        + "<tr><td>Pedro</td><td><button type=\"button\">Eliminar Pedro</button></td></tr>"
                        + "</tbody></table></main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");

        assertThat(result.elements()).filteredOn(element -> "button".equals(element.kind()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::controlType, ScreenAnalysisAdapter.DetectedElement::classification)
                .containsExactly(tuple(null, ActionClassification.MUTATING), tuple(null, ActionClassification.MUTATING),
                        tuple(null, ActionClassification.MUTATING));
    }

    @Test
    void fallsBackToNoDataItemsAndLogsOnlyTheExceptionTypeWhenDetectionFails() {
        Locator failing = (Locator) Proxy.newProxyInstance(Locator.class.getClassLoader(), new Class<?>[]{Locator.class},
                (proxy, method, args) -> { throw new PlaywrightException("page secret content"); });
        Logger logger = (Logger) LoggerFactory.getLogger(PlaywrightScreenAnalysisAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>(); appender.start(); logger.addAppender(appender);
        try {
            assertThat(PlaywrightScreenAnalysisAdapter.dataItems(failing)).isEmpty();
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains(PlaywrightException.class.getName()).doesNotContain("secret");
            });
        } finally { logger.detachAppender(appender); }
    }

    @Test
    void keepsWrappedToolbarButtonsAsActionsInsteadOfDataItems() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><main><h1>Documento</h1>"
                        + "<div class=\"actions\"><span class=\"btn-wrap\"><button type=\"button\">Guardar</button></span>"
                        + "<span class=\"btn-wrap\"><button type=\"button\">Eliminar</button></span>"
                        + "<span class=\"btn-wrap\"><button type=\"button\">Publicar</button></span></div>"
                        + "<div class=\"actions\"><span class=\"btn-wrap\"><button type=\"button\">Duplicar</button></span>"
                        + "<span class=\"btn-wrap\"><button type=\"button\">Archivar</button></span></div>"
                        + "<div role=\"toolbar\"><span class=\"tool\"><button type=\"button\">Crear copia</button><small>Ctrl+D</small></span>"
                        + "<span class=\"tool\"><button type=\"button\">Borrar todo</button><small>Ctrl+X</small></span></div>"
                        + "</main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");

        assertThat(result.elements()).filteredOn(element -> "button".equals(element.kind()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::controlType).doesNotContain("item");
        assertThat(result.elements()).filteredOn(element -> "button".equals(element.kind()))
                .filteredOn(element -> List.of("Guardar", "Eliminar", "Crear copia").contains(element.accessibleName()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::classification)
                .containsOnly(ActionClassification.MUTATING).hasSize(3);
    }

    @Test
    void keepsImplicitSubmitButtonsOfAFormOutOfDataItems() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><main><h1>Solicitud</h1><form>"
                        + "<div class=\"step\"><p>Conserva los cambios</p><button>Guardar borrador</button></div>"
                        + "<div class=\"step\"><p>Envia la solicitud</p><button>Enviar</button></div>"
                        + "<div class=\"step\"><p>Elimina el borrador</p><button>Eliminar borrador</button></div>"
                        + "</form></main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");

        assertThat(result.elements()).filteredOn(element -> "button".equals(element.kind()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::controlType, ScreenAnalysisAdapter.DetectedElement::classification)
                .containsExactly(tuple(null, ActionClassification.MUTATING), tuple(null, ActionClassification.MUTATING),
                        tuple(null, ActionClassification.MUTATING));
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

    @Test
    void waitsForRouteDataBeforeScanningControlsWhenOnlyHeaderControlsAreRenderedYet() throws Exception {
        var elements = analyzeDashboard("""
                <!doctype html><html><body><header><button>Menu</button></header><main><p>Cargando…</p></main>
                <script>
                fetch('/slow-data').then(response => response.json()).then(data => {
                  document.querySelector('main').innerHTML = '<h1>' + data.name + '</h1><form><label>Nombre <input name="name"></label>'
                    + '<button type="submit">Guardar cambios</button></form>';
                });
                </script></body></html>
                """, null, "<h1>Sign in page</h1>", 800).elements();
        assertThat(elements).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName).contains("Menu", "Nombre", "Guardar cambios");
    }

    @Test
    void treatsVisibleAriaBusyAsLoadingAndNavigationOnlyControlsAsNotReady() throws Exception {
        String renderLater = """
                <script>
                setTimeout(() => {
                  const main = document.querySelector('main');
                  main.removeAttribute('aria-busy');
                  main.innerHTML = '<h1>Backlog</h1><button>Crear sprint</button>';
                }, 1500);
                </script></body></html>
                """;
        var busy = analyzeDashboard("<!doctype html><html><body><header><button>Menu</button></header>"
                + "<main aria-busy=\"true\"><p>Cargando…</p></main>" + renderLater, null).elements();
        var navigationOnly = analyzeDashboard("<!doctype html><html><body><nav><a href=\"#home\">Inicio</a></nav>"
                + "<main></main>" + renderLater, null).elements();
        assertThat(busy).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName).contains("Crear sprint");
        assertThat(navigationOnly).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName).contains("Crear sprint");
    }

    @Test
    void fallsBackToThePreviousReadinessRuleWhenTheStrictCheckNeverHolds() throws Exception {
        var navigationOnly = analyzeDashboard("<!doctype html><html><body><nav><a href=\"#home\">Inicio</a></nav>"
                + "<div>Texto plano sin contenedor</div></body></html>", null);
        var permanentlyBusy = analyzeDashboard("<!doctype html><html><body><main aria-busy=\"true\"><h1>Panel</h1>"
                + "<button>Actualizar</button></main></body></html>", null);
        assertThat(navigationOnly.elements()).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName).contains("Inicio");
        assertThat(permanentlyBusy.elements()).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName).contains("Actualizar");
    }

    private static ScreenAnalysisAdapter.ScreenAnalysisResult analyzeDashboard(String dashboardHtml, String nextHtml) throws Exception {
        return analyzeDashboard(dashboardHtml, nextHtml, "<h1>Sign in page</h1>");
    }

    private static ScreenAnalysisAdapter.ScreenAnalysisResult analyzeDashboard(String dashboardHtml, String nextHtml, String loginHeading) throws Exception {
        return analyzeDashboard(dashboardHtml, nextHtml, loginHeading, 0);
    }

    /** slowDataDelayMillis > 0 also serves a JSON /slow-data endpoint that answers after that delay. */
    private static ScreenAnalysisAdapter.ScreenAnalysisResult analyzeDashboard(String dashboardHtml, String nextHtml, String loginHeading,
                                                                               long slowDataDelayMillis) throws Exception {
        return analyzeDashboard(dashboardHtml, nextHtml, loginHeading, slowDataDelayMillis, List.of(), server -> { });
    }

    /** Approved openers for the fixture application; extraContexts registers further fixture endpoints (e.g. request counters). */
    private static ScreenAnalysisAdapter.ScreenAnalysisResult analyzeDashboard(String dashboardHtml, String nextHtml, List<ApprovedOpenerRule> openers,
                                                                               Consumer<HttpServer> extraContexts) throws Exception {
        return analyzeDashboard(dashboardHtml, nextHtml, "<h1>Sign in page</h1>", 0, openers, extraContexts);
    }

    private static ScreenAnalysisAdapter.ScreenAnalysisResult analyzeDashboard(String dashboardHtml, String nextHtml, String loginHeading,
                                                                               long slowDataDelayMillis, List<ApprovedOpenerRule> openers,
                                                                               Consumer<HttpServer> extraContexts) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        extraContexts.accept(server);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();
        server.setExecutor(executor);
        if (slowDataDelayMillis > 0) server.createContext("/slow-data", exchange -> {
            try { Thread.sleep(slowDataDelayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] body = "{\"name\":\"Proyecto\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
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
            TargetApplication application = new TargetApplication("project", "app", baseUrl, baseUrl + "/login", "stored-user", "stored-password",
                    2, List.of(), openers);
            return new PlaywrightScreenAnalysisAdapter().analyze(application, "browser-user", "browser-password");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    /** Counts requests per "METHOD path" so tests can prove what did, and did not, reach the target server. */
    private static Consumer<HttpServer> countingRequests(Map<String, AtomicInteger> requests, String... paths) {
        return server -> {
            for (String path : paths) server.createContext(path, exchange -> {
                requests.computeIfAbsent(exchange.getRequestMethod() + " " + path, key -> new AtomicInteger()).incrementAndGet();
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
        };
    }

    private static int count(Map<String, AtomicInteger> requests, String key) {
        AtomicInteger value = requests.get(key);
        return value == null ? 0 : value.get();
    }

    /** A dashboard whose "Crear tarea" button opens a modal form; beforeOpen runs first in the click handler. */
    private static String dashboardWithTaskDialog(String beforeOpen, boolean escapeCloses) {
        return """
                <!doctype html><html><head><title>Tareas</title></head><body><main><h1>Tareas</h1>
                <button id="open" type="button">Crear tarea</button><button type="button" id="other">Eliminar todo</button>
                </main><script>
                const close = path => { document.getElementById('task-dialog')?.remove(); fetch(path); };
                document.getElementById('other').addEventListener('click', () => fetch('/unapproved-clicked'));
                document.getElementById('open').addEventListener('click', () => {
                  BEFORE_OPEN
                  const dialog = document.createElement('div');
                  dialog.id = 'task-dialog'; dialog.setAttribute('role', 'dialog'); dialog.setAttribute('aria-modal', 'true');
                  dialog.innerHTML = '<h2>Nueva tarea</h2><label>Titulo <input name="title"></label>'
                    + '<label for="priority">Prioridad</label><select id="priority"><option>Alta secreta</option></select>'
                    + '<a href="/from-dialog">Ayuda</a><button type="button" id="cancel">Cancelar</button><button type="submit">Guardar</button>';
                  document.body.appendChild(dialog);
                  document.getElementById('cancel').addEventListener('click', () => close('/closed-by-cancel'));
                });
                document.addEventListener('keydown', event => {
                  if (ESCAPE_CLOSES && event.key === 'Escape' && document.getElementById('task-dialog')) close('/closed-by-escape');
                });
                </script></body></html>
                """.replace("BEFORE_OPEN", beforeOpen).replace("ESCAPE_CLOSES", Boolean.toString(escapeCloses));
    }

    private static final String[] OPENER_ENDPOINTS = {"/closed-by-escape", "/closed-by-cancel", "/unapproved-clicked", "/api/tasks", "/from-dialog"};

    @Test
    void opensAnApprovedFormOpenerCapturesItsDialogFieldsAndClosesItWithEscape() throws Exception {
        Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        var result = analyzeDashboard(dashboardWithTaskDialog("", true), null,
                List.of(new ApprovedOpenerRule("/dashboard", "Crear tarea")), countingRequests(requests, OPENER_ENDPOINTS));

        assertThat(result.url()).endsWith("/dashboard");
        assertThat(result.openedForms()).hasSize(1);
        var form = result.openedForms().get(0);
        assertThat(form.openerName()).isEqualTo("Crear tarea");
        assertThat(form.openerSelector()).isEqualTo("button:nth-of-type(1)");
        assertThat(form.elements()).extracting(ScreenAnalysisAdapter.DetectedElement::kind, ScreenAnalysisAdapter.DetectedElement::accessibleName)
                .contains(tuple("input", "Titulo"), tuple("select", "Prioridad"), tuple("button", "Cancelar"), tuple("button", "Guardar"), tuple("a", "Ayuda"));
        assertThat(result.elements()).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName).doesNotContain("Titulo", "Prioridad");
        assertThat(count(requests, "GET /closed-by-escape")).isEqualTo(1);
        assertThat(count(requests, "GET /closed-by-cancel")).isZero();
        // Openers never enqueue links: the dialog's link is documented, not crawled.
        assertThat(count(requests, "GET /from-dialog")).isZero();
        assertThat(result.discoveredPages()).isEmpty();
    }

    @Test
    void neverPressesButtonsThatAreNotApprovedForThePageRoute() throws Exception {
        Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        var result = analyzeDashboard(dashboardWithTaskDialog("", true), null,
                List.of(new ApprovedOpenerRule("/dashboard", "Crear tarea"), new ApprovedOpenerRule("/reports", "Eliminar todo")),
                countingRequests(requests, OPENER_ENDPOINTS));

        assertThat(result.openedForms()).extracting(ScreenAnalysisAdapter.OpenedForm::openerName).containsExactly("Crear tarea");
        assertThat(count(requests, "GET /unapproved-clicked")).isZero();
    }

    @Test
    void pressesNothingWhenTheApplicationHasNoApprovedOpeners() throws Exception {
        Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        var result = analyzeDashboard(dashboardWithTaskDialog("", true), null, List.of(), countingRequests(requests, OPENER_ENDPOINTS));

        assertThat(result.openedForms()).isEmpty();
        assertThat(result.elements()).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName).contains("Crear tarea", "Eliminar todo");
        assertThat(requests).isEmpty();
    }

    @Test
    void blocksWritesFiredByAnOpenerAndDiscardsItsCaptureWithoutFailingTheAnalysis() throws Exception {
        Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        var result = analyzeDashboard(dashboardWithTaskDialog("fetch('/api/tasks', { method: 'POST', body: '{}' });", true), null,
                List.of(new ApprovedOpenerRule("/dashboard", "Crear tarea")), countingRequests(requests, OPENER_ENDPOINTS));

        assertThat(count(requests, "POST /api/tasks")).isZero();
        assertThat(result.openedForms()).isEmpty();
        assertThat(result.elements()).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName).contains("Crear tarea");
    }

    @Test
    void closesAnOpenedFormWithItsCancelButtonWhenEscapeDoesNotCloseIt() throws Exception {
        Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        var result = analyzeDashboard(dashboardWithTaskDialog("", false), null,
                List.of(new ApprovedOpenerRule("/dashboard", "Crear tarea")), countingRequests(requests, OPENER_ENDPOINTS));

        assertThat(result.openedForms()).extracting(ScreenAnalysisAdapter.OpenedForm::openerName).containsExactly("Crear tarea");
        assertThat(count(requests, "GET /closed-by-cancel")).isEqualTo(1);
        assertThat(count(requests, "GET /closed-by-escape")).isZero();
    }

    @Test
    void discardsAnOpenerThatChangesTheUrlRestoresThePageAndContinuesWithTheNextOpener() throws Exception {
        String dashboard = """
                <!doctype html><html><body><main><h1>Proyectos</h1>
                <button type="button" id="routed">Crear tarea</button><button type="button" id="modal">Crear proyecto</button>
                </main><script>
                document.getElementById('routed').addEventListener('click', () => {
                  history.pushState({}, '', '/dashboard/new');
                  document.body.insertAdjacentHTML('beforeend', '<div role="dialog"><label>Titulo <input name="title"></label></div>');
                });
                document.getElementById('modal').addEventListener('click', () => {
                  document.body.insertAdjacentHTML('beforeend', '<dialog open id="project"><label>Nombre <input name="name"></label></dialog>');
                });
                document.addEventListener('keydown', event => {
                  if (event.key === 'Escape') document.querySelectorAll('[role=dialog], dialog').forEach(d => d.remove());
                });
                </script></body></html>
                """;
        var result = analyzeDashboard(dashboard, null,
                List.of(new ApprovedOpenerRule("/dashboard", "Crear tarea"), new ApprovedOpenerRule("/dashboard", "Crear proyecto")), server -> { });

        assertThat(result.url()).endsWith("/dashboard");
        assertThat(result.openedForms()).extracting(ScreenAnalysisAdapter.OpenedForm::openerName).containsExactly("Crear proyecto");
        assertThat(result.openedForms().get(0).elements()).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName).containsExactly("Nombre");
    }

    @Test
    void opensApprovedOpenersOnCrawledPagesAndCapturesNewControlsWithoutADialog() throws Exception {
        String next = """
                <!doctype html><html><body><main><h1>Miembros</h1><button type="button" id="invite">Invitar miembro</button>
                <div id="slot"></div></main><script>
                document.getElementById('invite').addEventListener('click', () => {
                  document.getElementById('slot').innerHTML = '<label for="role">Rol</label><select id="role"><option>Admin</option></select>'
                    + '<button type="button" id="close">Cerrar</button>';
                  document.getElementById('close').addEventListener('click', () => { document.getElementById('slot').innerHTML = ''; });
                });
                </script></body></html>
                """;
        var result = analyzeDashboard("<!doctype html><html><body><main><h1>Panel</h1><a href=\"/next\">Miembros</a></main></body></html>",
                next, List.of(new ApprovedOpenerRule("/next", "Invitar miembro")), server -> { });

        assertThat(result.openedForms()).isEmpty();
        assertThat(result.discoveredPages()).hasSize(1);
        var forms = result.discoveredPages().get(0).openedForms();
        assertThat(forms).extracting(ScreenAnalysisAdapter.OpenedForm::openerName).containsExactly("Invitar miembro");
        assertThat(forms.get(0).elements()).extracting(ScreenAnalysisAdapter.DetectedElement::kind, ScreenAnalysisAdapter.DetectedElement::accessibleName)
                .containsExactlyInAnyOrder(tuple("select", "Rol"), tuple("button", "Cerrar"));
    }

    private static final String NEXT_PAGE = "<!doctype html><html><body><main><h1>Siguiente</h1><p>Contenido</p></main></body></html>";

    /**
     * Two approved openers ("Crear tarea" runs onTask before opening its dialog; "Crear proyecto" opens a plain dialog
     * closed by Escape), a GET /page-loaded beacon on every load, a link to /next, and onLoad run at page load.
     */
    private static String dashboardWithTwoOpeners(String onLoad, String onTask) {
        return """
                <!doctype html><html><head><title>Tareas</title></head><body><main><h1>Tareas</h1>
                <button type="button" id="task">Crear tarea</button><button type="button" id="project">Crear proyecto</button>
                <a href="/next">Siguiente</a></main><script>
                fetch('/page-loaded');
                const dialog = html => document.body.insertAdjacentHTML('beforeend', '<div role="dialog">' + html + '</div>');
                ON_LOAD
                document.getElementById('task').addEventListener('click', () => { ON_TASK
                  dialog('<label>Titulo <input name="title"></label>'); });
                document.getElementById('project').addEventListener('click', () => dialog('<label>Nombre <input name="name"></label>'));
                document.addEventListener('keydown', event => {
                  if (event.key === 'Escape') document.querySelectorAll('[role=dialog]').forEach(d => d.remove());
                });
                </script></body></html>
                """.replace("ON_LOAD", onLoad).replace("ON_TASK", onTask);
    }

    private static final List<ApprovedOpenerRule> BOTH_OPENERS =
            List.of(new ApprovedOpenerRule("/dashboard", "Crear tarea"), new ApprovedOpenerRule("/dashboard", "Crear proyecto"));

    private record Logged<T>(T result, List<String> messages) {}

    private static <T> Logged<T> withAdapterLogs(java.util.concurrent.Callable<T> action) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(PlaywrightScreenAnalysisAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            T result = action.call();
            return new Logged<>(result, appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void restoresThePageAfterABlockedWriteSoALaterOpenerIsCapturedAndCrawlingContinues() throws Exception {
        Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        var result = analyzeDashboard(dashboardWithTwoOpeners("", "fetch('/api/tasks', { method: 'POST', body: '{}' });"), NEXT_PAGE,
                BOTH_OPENERS, countingRequests(requests, "/page-loaded", "/api/tasks"));

        assertThat(count(requests, "POST /api/tasks")).isZero();
        // Initial load plus the restore after the discarded opener.
        assertThat(count(requests, "GET /page-loaded")).isEqualTo(2);
        assertThat(result.openedForms()).extracting(ScreenAnalysisAdapter.OpenedForm::openerName).containsExactly("Crear proyecto");
        assertThat(result.discoveredPages()).extracting(ScreenAnalysisAdapter.DiscoveredPage::heading).containsExactly("Siguiente");
    }

    @Test
    void discardsAnOpenerThatOpensAPopupAndContinuesTheAnalysis() throws Exception {
        Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        var logged = withAdapterLogs(() -> analyzeDashboard(dashboardWithTwoOpeners("", "window.open('/popup-page');"), NEXT_PAGE,
                BOTH_OPENERS, countingRequests(requests, "/page-loaded", "/popup-page")));

        assertThat(logged.messages()).anyMatch(message -> message.contains("category=popup-opened"));
        assertThat(logged.result().openedForms()).extracting(ScreenAnalysisAdapter.OpenedForm::openerName).containsExactly("Crear proyecto");
        assertThat(logged.result().discoveredPages()).hasSize(1);
    }

    @Test
    void blocksWebSocketFramesOnASocketOpenedBeforeTheOpenerAndDiscardsItsCapture() throws Exception {
        try (WebSocketFixtureServer sockets = new WebSocketFixtureServer()) {
            // The page reports itself busy until its load-time "hello" is sent, so readiness (initial load and the restore
            // after the discarded opener) waits for it: the hello can never land in the next opener's armed window.
            String onLoad = "const main = document.querySelector('main'); main.setAttribute('aria-busy', 'true');"
                    + "const socket = new WebSocket('" + sockets.url("/live") + "');"
                    + "socket.addEventListener('open', () => { socket.send('hello'); main.removeAttribute('aria-busy'); });";
            var logged = withAdapterLogs(() -> analyzeDashboard(dashboardWithTwoOpeners(onLoad, "socket.send('created');"), NEXT_PAGE,
                    BOTH_OPENERS, server -> { }));

            // Outside an opener the socket works normally, so the guard does not change baseline crawling.
            assertThat(sockets.awaitMessage("hello", 5_000)).isTrue();
            assertThat(sockets.received()).doesNotContain("created");
            assertThat(logged.messages()).anyMatch(message -> message.contains("category=websocket-message-blocked"));
            assertThat(logged.result().openedForms()).extracting(ScreenAnalysisAdapter.OpenedForm::openerName).containsExactly("Crear proyecto");
            assertThat(logged.result().discoveredPages()).hasSize(1);
        }
    }

    @Test
    void neverConnectsAWebSocketOpenedByAnOpenerAndDiscardsItsCapture() throws Exception {
        try (WebSocketFixtureServer sockets = new WebSocketFixtureServer()) {
            String onTask = "const opened = new WebSocket('" + sockets.url("/live") + "');"
                    + "opened.addEventListener('open', () => opened.send('from-opener'));";
            var logged = withAdapterLogs(() -> analyzeDashboard(dashboardWithTwoOpeners("", onTask), NEXT_PAGE, BOTH_OPENERS, server -> { }));

            assertThat(sockets.connections()).isZero();
            assertThat(sockets.received()).isEmpty();
            assertThat(logged.messages()).anyMatch(message -> message.contains("category=websocket-opened"));
            assertThat(logged.result().openedForms()).extracting(ScreenAnalysisAdapter.OpenedForm::openerName).containsExactly("Crear proyecto");
        }
    }

    @Test
    void closesAFormThatIgnoresEscapeAndHasNoCloseButtonByReloadingAndKeepsItsCapture() throws Exception {
        String dashboard = """
                <!doctype html><html><body><main><h1>Tareas</h1><button type="button" id="task">Crear tarea</button>
                <a href="/next">Siguiente</a></main><script>
                fetch('/page-loaded');
                document.getElementById('task').addEventListener('click', () => document.body.insertAdjacentHTML('beforeend',
                  '<div role="dialog"><label>Titulo <input name="title"></label><button type="button">Guardar borrador</button></div>'));
                </script></body></html>
                """;
        Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        var result = analyzeDashboard(dashboard, NEXT_PAGE, List.of(new ApprovedOpenerRule("/dashboard", "Crear tarea")),
                countingRequests(requests, "/page-loaded"));

        // Documented behavior: a reload that leaves no extra dialog counts as a successful close, so the capture is kept.
        assertThat(result.openedForms()).extracting(ScreenAnalysisAdapter.OpenedForm::openerName).containsExactly("Crear tarea");
        assertThat(result.openedForms().get(0).elements()).extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName)
                .contains("Titulo", "Guardar borrador");
        // Initial load plus the reload fallback; nothing was discarded, so no further restore.
        assertThat(count(requests, "GET /page-loaded")).isEqualTo(2);
        assertThat(result.discoveredPages()).hasSize(1);
    }

    @Test
    void discardsAFormThatStaysOpenAfterTheReloadFallbackAndContinuesTheAnalysis() throws Exception {
        // The opened dialog survives a reload (the page reopens it from session state), so closing fails.
        String dashboard = """
                <!doctype html><html><body><main><h1>Tareas</h1><button type="button" id="task">Crear tarea</button>
                <a href="/next">Siguiente</a></main><script>
                const show = () => document.body.insertAdjacentHTML('beforeend', '<div role="dialog"><label>Titulo <input name="title"></label></div>');
                if (sessionStorage.getItem('draft')) show();
                document.getElementById('task').addEventListener('click', () => { sessionStorage.setItem('draft', '1'); show(); });
                </script></body></html>
                """;
        var logged = withAdapterLogs(() -> analyzeDashboard(dashboard, NEXT_PAGE, List.of(new ApprovedOpenerRule("/dashboard", "Crear tarea")),
                server -> { }));

        assertThat(logged.messages()).anyMatch(message -> message.contains("category=close-failed"));
        assertThat(logged.result().openedForms()).isEmpty();
        assertThat(logged.result().discoveredPages()).extracting(ScreenAnalysisAdapter.DiscoveredPage::heading).containsExactly("Siguiente");
    }

    /** Serves NEXT_PAGE at /next only after the WebSocket fixture received every frame (bounded), keeping the previous page alive. */
    private static Consumer<HttpServer> nextPageAfterFrames(WebSocketFixtureServer sockets, String... frames) {
        return server -> server.createContext("/next", exchange -> {
            try { for (String frame : frames) sockets.awaitMessage(frame, 5_000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            respondHtml(exchange, NEXT_PAGE);
        });
    }

    @Test
    void closesAFormByReloadingWithoutDiscardingItWhenThePageWritesAndOpensASocketAtLoad() throws Exception {
        try (WebSocketFixtureServer sockets = new WebSocketFixtureServer()) {
            // Load-time traffic (a POST beacon and a socket) is ordinary page behavior; the opener itself writes nothing, and
            // the dialog ignores Escape and has no close button, so only the reload fallback closes it.
            String dashboard = """
                    <!doctype html><html><body><main><h1>Tareas</h1><button type="button" id="task">Crear tarea</button>
                    <a href="/next">Siguiente</a></main><script>
                    fetch('/api/visits', { method: 'POST', body: '{}' });
                    new WebSocket('WS_URL');
                    document.getElementById('task').addEventListener('click', () => document.body.insertAdjacentHTML('beforeend',
                      '<div role="dialog"><label>Titulo <input name="title"></label><button type="button">Guardar borrador</button></div>'));
                    </script></body></html>
                    """.replace("WS_URL", sockets.url("/live"));
            Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
            var logged = withAdapterLogs(() -> analyzeDashboard(dashboard, NEXT_PAGE, List.of(new ApprovedOpenerRule("/dashboard", "Crear tarea")),
                    countingRequests(requests, "/api/visits")));

            assertThat(logged.messages()).noneMatch(message -> message.contains("Approved opener capture discarded"));
            assertThat(logged.result().openedForms()).extracting(ScreenAnalysisAdapter.OpenedForm::openerName).containsExactly("Crear tarea");
            // One POST per document load (initial and reload); the press itself sent none.
            assertThat(count(requests, "POST /api/visits")).isEqualTo(2);
            assertThat(logged.result().discoveredPages()).extracting(ScreenAnalysisAdapter.DiscoveredPage::heading).containsExactly("Siguiente");
        }
    }

    @Test
    void blocksAWriteTheOpenedFormSchedulesRightAfterClosingAndDiscardsItsCapture() throws Exception {
        // Closing schedules a delayed "draft" POST; /next answers slowly so the page is still alive when the timer fires.
        String dashboard = """
                <!doctype html><html><body><main><h1>Tareas</h1><button type="button" id="task">Crear tarea</button>
                <a href="/next">Siguiente</a></main><script>
                document.getElementById('task').addEventListener('click', () => document.body.insertAdjacentHTML('beforeend',
                  '<div role="dialog"><label>Titulo <input name="title"></label></div>'));
                document.addEventListener('keydown', event => {
                  if (event.key !== 'Escape' || !document.querySelector('[role=dialog]')) return;
                  document.querySelectorAll('[role=dialog]').forEach(d => d.remove());
                  setTimeout(() => fetch('/api/drafts', { method: 'POST', body: '{}' }), 500);
                });
                </script></body></html>
                """;
        Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        Consumer<HttpServer> contexts = countingRequests(requests, "/api/drafts").andThen(server -> server.createContext("/next", exchange -> {
            try { Thread.sleep(1_500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            respondHtml(exchange, NEXT_PAGE);
        }));
        var logged = withAdapterLogs(() -> analyzeDashboard(dashboard, null, List.of(new ApprovedOpenerRule("/dashboard", "Crear tarea")), contexts));

        assertThat(count(requests, "POST /api/drafts")).isZero();
        assertThat(logged.messages()).anyMatch(message -> message.contains("category=write-request-blocked"));
        assertThat(logged.result().openedForms()).isEmpty();
        assertThat(logged.result().discoveredPages()).hasSize(1);
    }

    @Test
    void letsThePageSendFramesAndOpenSocketsAgainAfterAKeptOpener() throws Exception {
        try (WebSocketFixtureServer sockets = new WebSocketFixtureServer()) {
            // After closing, the page waits until the guard is disarmed, then writes on its load-time socket and opens a new one.
            String dashboard = """
                    <!doctype html><html><body><main><h1>Tareas</h1><button type="button" id="task">Crear tarea</button>
                    <a href="/next">Siguiente</a></main><script>
                    const socket = new WebSocket('WS_URL');
                    document.getElementById('task').addEventListener('click', () => document.body.insertAdjacentHTML('beforeend',
                      '<div role="dialog"><label>Titulo <input name="title"></label></div>'));
                    document.addEventListener('keydown', event => {
                      if (event.key !== 'Escape' || !document.querySelector('[role=dialog]')) return;
                      document.querySelectorAll('[role=dialog]').forEach(d => d.remove());
                      const guard = window['__systemGuideForgeWebSocketGuard'];
                      const wait = setInterval(() => {
                        if (!guard || guard.active) return;
                        clearInterval(wait);
                        socket.send('after-opener');
                        const fresh = new WebSocket('WS_URL');
                        fresh.addEventListener('open', () => fresh.send('new-socket'));
                      }, 20);
                    });
                    </script></body></html>
                    """.replace("WS_URL", sockets.url("/live"));
            var logged = withAdapterLogs(() -> analyzeDashboard(dashboard, null, List.of(new ApprovedOpenerRule("/dashboard", "Crear tarea")),
                    nextPageAfterFrames(sockets, "after-opener", "new-socket")));

            assertThat(logged.result().openedForms()).extracting(ScreenAnalysisAdapter.OpenedForm::openerName).containsExactly("Crear tarea");
            assertThat(sockets.received()).contains("after-opener", "new-socket");
            assertThat(sockets.connections()).isEqualTo(2);
            assertThat(logged.result().discoveredPages()).hasSize(1);
        }
    }

    @Test
    void discardsACaptureWhenTheOpenedFormWritesAfterTheGracePeriodCheckWhileTheGuardIsStillInstalled() throws Exception {
        try (WebSocketFixtureServer sockets = new WebSocketFixtureServer()) {
            // Deterministic timing without a production seam: the page observes the adapter's counter reads (the first after
            // the press, the second after the grace period) and sends a frame right after the second one, i.e. after the last
            // in-flow check but while the guard is still armed. The frame is dropped; the capture must not be kept.
            String dashboard = """
                    <!doctype html><html><body><main><h1>Tareas</h1><button type="button" id="task">Crear tarea</button>
                    <a href="/next">Siguiente</a></main><script>
                    const socket = new WebSocket('WS_URL');
                    const guard = window['__systemGuideForgeWebSocketGuard'];
                    let blocked = 0, reads = 0;
                    Object.defineProperty(guard, 'blockedMessages', {
                      get() { reads++; if (reads === 2) queueMicrotask(() => { try { socket.send('late'); } catch (e) { } }); return blocked; },
                      set(value) { blocked = value; }
                    });
                    document.getElementById('task').addEventListener('click', () => document.body.insertAdjacentHTML('beforeend',
                      '<div role="dialog"><label>Titulo <input name="title"></label></div>'));
                    document.addEventListener('keydown', event => {
                      if (event.key === 'Escape') document.querySelectorAll('[role=dialog]').forEach(d => d.remove());
                    });
                    </script></body></html>
                    """.replace("WS_URL", sockets.url("/live"));
            var logged = withAdapterLogs(() -> analyzeDashboard(dashboard, NEXT_PAGE, List.of(new ApprovedOpenerRule("/dashboard", "Crear tarea")),
                    server -> { }));

            assertThat(sockets.received()).doesNotContain("late");
            assertThat(logged.messages()).anyMatch(message -> message.contains("category=websocket-message-blocked"));
            assertThat(logged.result().openedForms()).isEmpty();
            assertThat(logged.result().discoveredPages()).extracting(ScreenAnalysisAdapter.DiscoveredPage::heading).containsExactly("Siguiente");
        }
    }

    @Test
    void keepsASharedNavigationOpenerOnEveryPageWhoseOpenedFormReferencesIt() {
        ScreenAnalysisAdapter.DetectedElement create = new ScreenAnalysisAdapter.DetectedElement("button", "button:nth-of-type(1)", "Crear proyecto", ActionClassification.MUTATING, null, true);
        ScreenAnalysisAdapter.DetectedElement logout = new ScreenAnalysisAdapter.DetectedElement("button", "button:nth-of-type(2)", "Cerrar sesión", ActionClassification.MUTATING, null, true);
        ScreenAnalysisAdapter.DetectedElement name = new ScreenAnalysisAdapter.DetectedElement("input", "input:nth-of-type(1)", "Nombre", ActionClassification.UNKNOWN);
        ScreenAnalysisAdapter.OpenedForm form = new ScreenAnalysisAdapter.OpenedForm("Crear proyecto", "button:nth-of-type(1)", List.of(name));
        ScreenAnalysisAdapter.ScreenAnalysisResult first = new ScreenAnalysisAdapter.ScreenAnalysisResult(
                "http://localhost/", "Home", List.of(create, logout), new byte[]{1});
        ScreenAnalysisAdapter.DiscoveredPage withForm = new ScreenAnalysisAdapter.DiscoveredPage(
                "http://localhost/projects", "Projects", List.of(create, logout), new byte[]{1}, 1, ActionClassification.SAFE, null, List.of(form));
        ScreenAnalysisAdapter.DiscoveredPage withoutForm = new ScreenAnalysisAdapter.DiscoveredPage(
                "http://localhost/reports", "Reports", List.of(create, logout), new byte[]{1}, 1, ActionClassification.SAFE, null, List.of());

        ScreenAnalysisAdapter.ScreenAnalysisResult result =
                PlaywrightScreenAnalysisAdapter.withSharedNavigationIncludedOnce(first, List.of(withForm, withoutForm));

        // The opener stays where its opened form needs it; other shared navigation is still included only once.
        assertThat(result.discoveredPages().get(0).elements()).containsExactly(create);
        assertThat(result.discoveredPages().get(0).openedForms()).containsExactly(form);
        assertThat(result.discoveredPages().get(1).elements()).isEmpty();
    }

    @Test
    void derivesTheOpenerDomIndexFromItsOwnPageSelectorOnly() {
        assertThat(PlaywrightScreenAnalysisAdapter.buttonIndex("button:nth-of-type(1)")).isZero();
        assertThat(PlaywrightScreenAnalysisAdapter.buttonIndex("button:nth-of-type(12)")).isEqualTo(11);
        assertThat(PlaywrightScreenAnalysisAdapter.buttonIndex("a:nth-of-type(2)")).isEqualTo(-1);
        assertThat(PlaywrightScreenAnalysisAdapter.buttonIndex("button:nth-of-type(0)")).isEqualTo(-1);
        assertThat(PlaywrightScreenAnalysisAdapter.buttonIndex(null)).isEqualTo(-1);
    }

    @Test
    void logsAnApprovedOpenerThatCanNoLongerBeFoundInsteadOfSkippingItSilently() throws Exception {
        // Closing the first form renames the second opener, so its re-check before the press fails.
        String onLoad = "document.addEventListener('keydown', event => { if (event.key === 'Escape')"
                + " document.getElementById('project').textContent = 'Proyecto archivado'; });";
        var logged = withAdapterLogs(() -> analyzeDashboard(dashboardWithTwoOpeners(onLoad, ""), NEXT_PAGE, BOTH_OPENERS, server -> { }));

        assertThat(logged.result().openedForms()).extracting(ScreenAnalysisAdapter.OpenedForm::openerName).containsExactly("Crear tarea");
        assertThat(logged.messages()).anyMatch(message -> message.contains("category=opener-not-found"));
        assertThat(logged.result().discoveredPages()).hasSize(1);
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
    void flagsButtonsInsideNavigationLandmarksLikeLinks() throws Exception {
        ScreenAnalysisAdapter.ScreenAnalysisResult result = analyzeDashboard(
                "<!doctype html><html><body><header><button>Cerrar sesión</button></header><nav><button>Abrir menú</button></nav>"
                        + "<main><h1>Home</h1><button>Guardar</button></main></body></html>",
                "<!doctype html><html><body><main><h1>Next</h1></main></body></html>");

        assertThat(result.elements()).filteredOn(element -> "button".equals(element.kind()))
                .extracting(ScreenAnalysisAdapter.DetectedElement::accessibleName, ScreenAnalysisAdapter.DetectedElement::inNavigation)
                .containsExactlyInAnyOrder(tuple("Cerrar sesión", true), tuple("Abrir menú", true), tuple("Guardar", false));
    }

    @Test
    void keepsAGlobalNavigationButtonOnlyOnTheStartPageWhilePageButtonsStayOnEveryPage() {
        ScreenAnalysisAdapter.DetectedElement logout = new ScreenAnalysisAdapter.DetectedElement("button", "button:nth-of-type(1)", "Cerrar sesión", ActionClassification.MUTATING, null, true);
        ScreenAnalysisAdapter.DetectedElement save = new ScreenAnalysisAdapter.DetectedElement("button", "button:nth-of-type(2)", "Guardar", ActionClassification.MUTATING, null, false);
        ScreenAnalysisAdapter.DetectedElement contentLogout = new ScreenAnalysisAdapter.DetectedElement("button", "button:nth-of-type(3)", "Cerrar sesión", ActionClassification.MUTATING, null, false);
        ScreenAnalysisAdapter.ScreenAnalysisResult first = new ScreenAnalysisAdapter.ScreenAnalysisResult(
                "http://localhost/", "Home", List.of(logout, save), new byte[]{1});
        ScreenAnalysisAdapter.DiscoveredPage profile = new ScreenAnalysisAdapter.DiscoveredPage(
                "http://localhost/profile", "Perfil", List.of(logout, save, contentLogout), new byte[]{1}, 1, ActionClassification.SAFE);

        ScreenAnalysisAdapter.ScreenAnalysisResult result =
                PlaywrightScreenAnalysisAdapter.withSharedNavigationIncludedOnce(first, List.of(profile));

        assertThat(result.elements()).containsExactly(logout, save);
        assertThat(result.discoveredPages()).singleElement()
                .satisfies(page -> assertThat(page.elements()).containsExactly(save, contentLogout));
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
