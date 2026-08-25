package com.systemguideforge.backend.application;

import com.microsoft.playwright.*;
import com.systemguideforge.backend.persistence.TargetApplication;
import org.springframework.stereotype.Component;
import java.util.*;

/** Runs one authenticated, isolated browser context and never performs discovered actions. */
@Component
public final class PlaywrightScreenAnalysisAdapter implements ScreenAnalysisAdapter {
    static final String SENSITIVE_FIELD_SELECTOR =
            "input[type='password'], input[autocomplete='current-password'], "
                    + "input[autocomplete='new-password'], input[autocomplete='one-time-code'], "
                    + "input[name*='password' i], input[name*='secret' i], input[name*='token' i], "
                    + "input[name*='credential' i], input[name*='api-key' i], input[id*='password' i], "
                    + "input[id*='secret' i], input[id*='token' i], input[placeholder*='password' i], "
                    + "input[aria-label*='password' i], textarea[name*='password' i], "
                    + "textarea[name*='secret' i], textarea[name*='token' i], [data-sensitive]";

    private final BrowserFactory browserFactory;
    public PlaywrightScreenAnalysisAdapter() { this(Playwright::create); }
    PlaywrightScreenAnalysisAdapter(BrowserFactory browserFactory) { this.browserFactory = browserFactory; }

    static boolean isAuthenticatedAfterRedirect(String candidateUrl, TargetApplication application) {
        return PlaywrightAccessProbe.isSuccessfulRedirect(candidateUrl, application.getBaseUrl(), application.getLoginUrl());
    }

    @Override
    public ScreenAnalysisResult analyze(TargetApplication application, String username, String password) {
        try (Playwright playwright = browserFactory.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             BrowserContext context = browser.newContext()) {
            com.microsoft.playwright.Page page = context.newPage();
            page.navigate(application.getLoginUrl());
            Locator user = page.locator("input[name='username'], input[name='email'], input[type='email'], input[type='text']").first();
            Locator pass = page.locator("input[type='password']").first();
            Locator submit = page.locator("button[type='submit'], input[type='submit']").first();
            if (user.count() == 0 || pass.count() == 0 || submit.count() == 0) throw new IllegalStateException("Login form unavailable");
            user.fill(username); pass.fill(password); submit.click();
            page.waitForURL(url -> isAuthenticatedAfterRedirect(url, application),
                    new Page.WaitForURLOptions().setTimeout(10_000));
            if (!isAuthenticatedAfterRedirect(page.url(), application)) throw new IllegalStateException("Authentication failed");
            List<DetectedElement> found = new ArrayList<>();
            detect(page, "button", found); detect(page, "a", found); detect(page, "input", found); detect(page, "textarea", found);
            Locator sensitiveFields = page.locator(SENSITIVE_FIELD_SELECTOR);
            sensitiveFields.count();
            byte[] screenshot = page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions()
                    .setFullPage(true).setMask(List.of(sensitiveFields)));
            if (screenshot.length == 0) throw new IllegalStateException("Sanitized screenshot unavailable");
            return new ScreenAnalysisResult(safeUrl(page.url()), safe(page.title()), found, screenshot);
        } catch (Exception e) { throw new IllegalStateException("Screen analysis unavailable or failed", e); }
    }

    private void detect(com.microsoft.playwright.Page page, String kind, List<DetectedElement> found) {
        Locator locator = page.locator(kind); int count = Math.min(locator.count(), 500);
        for (int i = 0; i < count; i++) {
            Locator item = locator.nth(i);
            String selector = kind + ":nth-of-type(" + (i + 1) + ")";
            String aria = item.getAttribute("aria-label"); String name = item.getAttribute("name");
            String id = item.getAttribute("id"); String placeholder = item.getAttribute("placeholder");
            String type = item.getAttribute("type"); String href = item.getAttribute("href");
            String label = first(aria, name, placeholder, text(item));
            String attribute = "a".equals(kind) ? "href" : "type";
            String value = "a".equals(kind) ? href : type;
            found.add(new DetectedElement(kind, selector, safe(label),
                    classifyFixtureElement(item, kind, attribute, value, name, id, placeholder)));
        }
    }

    private static ActionClassification classifyFixtureElement(Locator item, String kind, String attribute,
                                                                 String value, String name, String id, String placeholder) {
        if (isSensitiveField(kind, value, name, id, placeholder)) return ActionClassification.UNKNOWN;
        ActionClassification metadata = ActionClassifier.classifyFixtureMetadata(item.getAttribute("data-action-type"));
        return metadata != null ? metadata : ActionClassifier.classify(kind, attribute, value);
    }

    private static boolean isSensitiveField(String kind, String type, String name, String id, String placeholder) {
        if (!"input".equals(kind) && !"textarea".equals(kind)) return false;
        String fields = String.join(" ", type == null ? "" : type, name == null ? "" : name,
                id == null ? "" : id, placeholder == null ? "" : placeholder);
        return fields.matches("(?i).*(password|api[-_]?key|secret|token|credential).*" );
    }

    private static String text(Locator l) { try { return l.textContent(); } catch (Exception e) { return null; } }
    private static String first(String... values) { for (String v : values) if (v != null && !v.isBlank()) return v.trim(); return null; }
    private static String safe(String value) { return value == null ? null : value.replaceAll("(?i)password|secret|credential|token|api[-_]?key", "[redacted]"); }
    private static String safeUrl(String value) { try { java.net.URI uri = java.net.URI.create(value); return new java.net.URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toString(); } catch (Exception e) { return "about:blank"; } }
    @FunctionalInterface interface BrowserFactory { Playwright create(); }
}
