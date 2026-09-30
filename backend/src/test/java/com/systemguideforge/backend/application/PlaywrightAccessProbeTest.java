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
        assertThat(result.code()).isEqualTo(AccessResultCode.BROWSER_LOGIN_FAILED);
    }

    @Test
    void reportsStableCodesForMissingFormRejectedAndAcceptedLogins() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String form = "<html><body><form method=\"post\"><input name=\"username\" type=\"text\"><input name=\"password\" type=\"password\"><button type=\"submit\">Go</button></form></body></html>";
        server.createContext("/nologin", exchange -> respond(exchange, 200, "<html><body>nothing</body></html>", null));
        server.createContext("/ok", exchange -> respond(exchange, "POST".equals(exchange.getRequestMethod()) ? 302 : 200, form, "/home"));
        server.createContext("/home", exchange -> respond(exchange, 200, "<html><body>Welcome</body></html>", null));
        server.createContext("/rejected", exchange -> respond(exchange, 200, form, null));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            AccessProbe probe = new PlaywrightAccessProbe();

            assertThat(probe.test(new AccessRequest(base, base + "/nologin", "user", "pw")).code()).isEqualTo(AccessResultCode.LOGIN_FORM_UNAVAILABLE);
            AccessResult accepted = probe.test(new AccessRequest(base, base + "/ok", "user", "pw"));
            assertThat(accepted.code()).isEqualTo(AccessResultCode.AUTHENTICATED);
            assertThat(accepted.authenticated()).isTrue();
            assertThat(probe.test(new AccessRequest(base, base + "/rejected", "user", "pw")).code()).isEqualTo(AccessResultCode.NOT_AUTHENTICATED);
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String html, String location) throws java.io.IOException {
        byte[] body = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        if (location != null && status == 302) exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(status, status == 302 ? -1 : body.length);
        if (status != 302) exchange.getResponseBody().write(body);
        exchange.close();
    }
}
