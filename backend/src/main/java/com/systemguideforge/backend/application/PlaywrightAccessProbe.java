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

            boolean passwordFieldVisible = passwordField.isVisible();
            return new AccessResult(true, !passwordFieldVisible,
                    passwordFieldVisible ? "Login rejected or still pending" : "Login successful");
        } catch (Exception ex) {
            return new AccessResult(false, false, "Browser login unavailable or failed");
        }
    }

    @FunctionalInterface
    interface BrowserFactory {
        Playwright create();
    }
}
