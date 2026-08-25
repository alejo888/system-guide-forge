package com.systemguideforge.backend.application;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.springframework.stereotype.Component;

/** Performs a real, isolated traditional login without exposing credentials to logs or results. */
@Component
public final class PlaywrightAccessProbe implements AccessProbe {
    private final BrowserFactory browserFactory;

    public PlaywrightAccessProbe() {
        this(Playwright::create);
    }

    PlaywrightAccessProbe(BrowserFactory browserFactory) {
        this.browserFactory = browserFactory;
    }

    @Override
    public AccessResult test(AccessRequest request) {
        try (Playwright playwright = browserFactory.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.navigate(request.loginUrl());
            var usernameField = page.locator("input[name='username'], input[name='email'], input[type='email'], input[type='text']").first();
            var passwordField = page.locator("input[type='password']").first();
            var submitButton = page.locator("button[type='submit'], input[type='submit']").first();
            if (usernameField.count() == 0 || passwordField.count() == 0 || submitButton.count() == 0) {
                return new AccessResult(true, false, "Login form unavailable");
            }
            usernameField.fill(request.username());
            passwordField.fill(request.password());
            submitButton.click();
            page.waitForURL(url -> isSuccessfulRedirect(url, request),
                    new Page.WaitForURLOptions().setTimeout(10_000));

            boolean authenticated = isAuthenticatedAfterRedirect(page.url(), request);
            return new AccessResult(true, authenticated,
                    authenticated ? "Login successful" : "Login rejected or still pending");
        } catch (Exception ex) {
            return new AccessResult(false, false, "Browser login unavailable or failed");
        }
    }

    static boolean isAuthenticatedAfterRedirect(String candidateUrl, AccessRequest request) {
        return isSuccessfulRedirect(candidateUrl, request);
    }

    static boolean isSuccessfulRedirect(String candidateUrl, AccessRequest request) {
        return isSuccessfulRedirect(candidateUrl, request.baseUrl(), request.loginUrl());
    }

    static boolean isSuccessfulRedirect(String candidateUrl, String baseUrl, String loginUrl) {
        try {
            var candidate = java.net.URI.create(candidateUrl);
            var base = java.net.URI.create(baseUrl);
            var login = java.net.URI.create(loginUrl);
            if (!candidate.isAbsolute() || !base.isAbsolute() || !login.isAbsolute()
                    || !sameOrigin(base, login) || !sameOrigin(candidate, base)
                    || samePath(candidate, login)) {
                return false;
            }
            String basePath = normalizedPath(base);
            String candidatePath = normalizedPath(candidate);
            return "/".equals(basePath) || candidatePath.equals(basePath)
                    || candidatePath.startsWith(basePath + "/");
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean sameOrigin(java.net.URI left, java.net.URI right) {
        return left.getScheme() != null && left.getHost() != null
                && right.getScheme() != null && right.getHost() != null
                && java.util.Objects.equals(left.getScheme(), right.getScheme())
                && java.util.Objects.equals(left.getHost(), right.getHost())
                && left.getPort() == right.getPort();
    }

    private static boolean samePath(java.net.URI left, java.net.URI right) {
        return normalizedPath(left).equals(normalizedPath(right));
    }

    private static String normalizedPath(java.net.URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    @FunctionalInterface
    interface BrowserFactory {
        Playwright create();
    }
}
