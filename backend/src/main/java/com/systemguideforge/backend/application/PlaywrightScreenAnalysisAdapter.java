package com.systemguideforge.backend.application;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.systemguideforge.backend.persistence.ApprovedOpenerRule;
import com.systemguideforge.backend.persistence.TargetApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Runs one authenticated, isolated browser context and never performs discovered actions. */
@Component
public final class PlaywrightScreenAnalysisAdapter implements ScreenAnalysisAdapter {
    static final String SENSITIVE_FIELD_SELECTOR =
            "input[type='password'], input[autocomplete='current-password'], " +
                    "input[autocomplete='new-password'], input[autocomplete='one-time-code'], " +
                    "input[name*='password' i], input[name*='secret' i], input[name*='token' i], " +
                    "input[name*='credential' i], input[name*='api-key' i], input[id*='password' i], " +
                    "input[id*='secret' i], input[id*='token' i], input[placeholder*='password' i], " +
                    "input[aria-label*='password' i], textarea[name*='password' i], " +
                    "textarea[name*='secret' i], textarea[name*='token' i], [data-sensitive]";
    private static final Logger LOG = LoggerFactory.getLogger(PlaywrightScreenAnalysisAdapter.class);
    /** Elements captured per kind on one page; data-item detection scans the same prefix so indices stay aligned. */
    private static final int MAX_ELEMENTS_PER_KIND = 500;
    private final BrowserFactory browserFactory;
    private final LoginCapture loginCapture;
    public PlaywrightScreenAnalysisAdapter() { this(Playwright::create); }
    PlaywrightScreenAnalysisAdapter(BrowserFactory browserFactory) { this(browserFactory, null); }
    /** Test seam: overrides only the pre-login capture, to exercise the best-effort failure path without a real browser fault. */
    PlaywrightScreenAnalysisAdapter(BrowserFactory browserFactory, LoginCapture loginCaptureOverride) {
        this.browserFactory = browserFactory;
        this.loginCapture = loginCaptureOverride != null ? loginCaptureOverride : (page, app) -> analyzeCurrentPage(page, app, 0);
    }

    static boolean isAuthenticatedAfterRedirect(String candidateUrl, TargetApplication application) {
        return PlaywrightAccessProbe.isSuccessfulRedirect(candidateUrl, application.getBaseUrl(), application.getLoginUrl());
    }

    @Override
    public ScreenAnalysisResult analyze(TargetApplication application, String username, String password) {
        try (Playwright playwright = browserFactory.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             // Service workers could answer or forward requests outside the opener request guard, so none are allowed.
             BrowserContext context = browser.newContext(new Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK))) {
            com.microsoft.playwright.Page page = context.newPage();
            page.navigate(application.getLoginUrl());
            Locator user = page.locator("input[name='username'], input[name='email'], input[type='email'], input[type='text']").first();
            Locator pass = page.locator("input[type='password']").first();
            Locator submit = page.locator("button[type='submit'], input[type='submit']").first();
            if (user.count() == 0 || pass.count() == 0 || submit.count() == 0) throw new IllegalStateException("Login form unavailable");
            LoginPage loginPage = captureLoginPage(page, application, user, pass, submit);
            user.fill(username); pass.fill(password); submit.click();
            page.waitForURL(url -> isAuthenticatedAfterRedirect(url, application), new Page.WaitForURLOptions().setTimeout(10_000));
            if (!isAuthenticatedAfterRedirect(page.url(), application)) throw new IllegalStateException("Authentication failed");
            if (!isSafeNavigationResult(page.url(), application)) throw new IllegalStateException("Unsafe authenticated redirect rejected");

            List<ApprovedOpenerRule> approvedOpeners = application.getApprovedOpeners();
            ApprovedOpenerMatcher openers = approvedOpeners.isEmpty() ? null : new ApprovedOpenerMatcher(approvedOpeners);
            String startUrl = safeUrl(page.url());
            CrawlBudget budget = new CrawlBudget(AnalysisService.MAX_CRAWL_PAGES, AnalysisService.MAX_CRAWL_LINKS);
            if (!budget.takePage()) throw new IllegalStateException("Crawl page budget unavailable");
            ScreenAnalysisResult first = analyzeCurrentPage(page, application, 0);
            List<DiscoveredPage> discovered = new ArrayList<>();
            Set<String> visited = new HashSet<>(Set.of(startUrl));
            ArrayDeque<Link> queue = new ArrayDeque<>(links(page, application, 1, budget));
            // Links are collected before any opener is pressed, so an opened form never adds pages to the crawl (RF08).
            first = new ScreenAnalysisResult(first.url(), first.title(), first.elements(), first.sanitizedScreenshot(), first.discoveredPages(),
                    first.loginPage(), first.heading(), openApprovedForms(page, application, openers, first.elements()));
            while (!queue.isEmpty()) {
                Link link = queue.removeFirst();
                if (link.depth > application.getMaxCrawlDepth() || application.isExcludedPath(link.url) || !visited.add(link.url) || !budget.takePage()) continue;
                page.navigate(link.url);
                if (isLoginNavigationResult(page.url(), application)) continue;
                if (!isSafeNavigationResult(page.url(), application)) {
                    throw new IllegalStateException("Unsafe navigation redirect rejected");
                }
                ScreenAnalysisResult current = analyzeCurrentPage(page, application, link.depth);
                List<Link> next = link.depth < application.getMaxCrawlDepth() ? links(page, application, link.depth + 1, budget) : List.of();
                List<OpenedForm> openedForms = openApprovedForms(page, application, openers, current.elements());
                discovered.add(new DiscoveredPage(current.url(), current.title(), current.elements(), current.sanitizedScreenshot(), link.depth, ActionClassification.SAFE, current.heading(), openedForms));
                queue.addAll(next);
            }
            ScreenAnalysisResult combined = withSharedNavigationIncludedOnce(first, discovered);
            return new ScreenAnalysisResult(combined.url(), combined.title(), combined.elements(), combined.sanitizedScreenshot(), combined.discoveredPages(), loginPage, combined.heading(), combined.openedForms());
        } catch (Exception e) {
            String category = failureCategory(e);
            LOG.warn("Screen analysis failed: category={}, exception={}, origin={}", category, e.getClass().getName(), failureOrigin(e));
            throw new IllegalStateException("Screen analysis unavailable or failed: " + category);
        }
    }

    /** Best-effort: the pre-login capture never blocks sign-in and the crawl that follows it. */
    private LoginPage captureLoginPage(com.microsoft.playwright.Page page, TargetApplication application, Locator user, Locator pass, Locator submit) {
        try {
            ScreenAnalysisResult capture = loginCapture.capture(page, application);
            return new LoginPage(capture.url(), capture.title(), capture.elements(), capture.sanitizedScreenshot(),
                    safeLabel(controlLabel(user)), safeLabel(controlLabel(pass)), safeLabel(submitLabel(submit)), capture.heading());
        } catch (RuntimeException e) {
            LOG.warn("Login page capture failed: category={}, exception={}, origin={}", failureCategory(e), e.getClass().getName(), failureOrigin(e));
            return null;
        }
    }

    /** Associated <label> text first, then aria-label, then placeholder; never the field value. */
    private static String controlLabel(Locator locator) {
        if (locator.count() == 0) return null;
        Object associatedLabel = locator.evaluate(
                "el => { const labels = el.labels ? Array.from(el.labels) : [];"
                        + "const text = labels.map(l => (l.textContent || '').trim()).find(t => t);"
                        + "return text || null; }");
        if (associatedLabel instanceof String label && !label.isBlank()) return label;
        String ariaLabel = locator.getAttribute("aria-label");
        if (ariaLabel != null && !ariaLabel.isBlank()) return ariaLabel;
        String placeholder = locator.getAttribute("placeholder");
        if (placeholder != null && !placeholder.isBlank()) return placeholder;
        return null;
    }

    /** Submit control label: same priority as controlLabel, then its own visible text. */
    private static String submitLabel(Locator locator) {
        String label = controlLabel(locator);
        if (label != null) return label;
        String visibleText = text(locator);
        return visibleText != null && !visibleText.isBlank() ? visibleText : null;
    }

    /** Fits the login label columns (VARCHAR(255)) and matches the manual's read-back cap. */
    static final int MAX_LOGIN_LABEL_LENGTH = 200;

    static String safeLabel(String label) {
        if (label == null) return null;
        String normalized = label.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return null;
        String redacted = safe(normalized);
        // Never cut: a cut could split a redaction marker, so oversized labels fall back to generic manual wording.
        return redacted.length() <= MAX_LOGIN_LABEL_LENGTH ? redacted : null;
    }

    /** Code location only: exception messages may echo target URLs, selectors, or credentials. */
    private static String failureOrigin(Exception error) {
        StackTraceElement[] frames = error.getStackTrace();
        return frames.length == 0 ? "unknown" : frames[0].toString();
    }

    private static String failureCategory(Exception error) {
        String message = error.getMessage();
        if (message == null) return "Browser operation failed";
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("timeout") || lower.contains("timed out")) return "Browser operation timeout";
        if (lower.contains("authentication failed") || lower.contains("login form unavailable")) return "Authentication failed";
        if (lower.contains("unsafe") || lower.contains("redirect")) return "Unsafe navigation rejected";
        if (lower.contains("screenshot")) return "Sanitized screenshot unavailable";
        if (lower.contains("navigation") || lower.contains("navigate")) return "Browser navigation failed";
        if (lower.contains("browser") || lower.contains("launch")) return "Browser unavailable";
        return "Browser operation failed";
    }

    static ScreenAnalysisResult withSharedNavigationIncludedOnce(ScreenAnalysisResult first, List<DiscoveredPage> discovered) {
        Set<String> sharedNavigation = sharedNavigationKeys(Stream.concat(
                Stream.of(first.elements()), discovered.stream().map(DiscoveredPage::elements)).toList());
        if (sharedNavigation.isEmpty()) return new ScreenAnalysisResult(first.url(), first.title(), first.elements(), first.sanitizedScreenshot(), discovered, null, first.heading(), first.openedForms());
        List<DiscoveredPage> withoutRepeatedNavigation = discovered.stream()
                .map(page -> new DiscoveredPage(page.url(), page.title(),
                        page.elements().stream().filter(element -> !isSharedNavigation(element, sharedNavigation)).toList(),
                        page.sanitizedScreenshot(), page.depth(), ActionClassification.SAFE, page.heading(), page.openedForms()))
                .toList();
        return new ScreenAnalysisResult(first.url(), first.title(), first.elements(), first.sanitizedScreenshot(), withoutRepeatedNavigation, null, first.heading(), first.openedForms());
    }

    /** Immutable sets reject contains(null), so elements without a navigation key are never shared navigation. */
    private static boolean isSharedNavigation(DetectedElement element, Set<String> sharedNavigation) {
        String key = navigationKey(element);
        return key != null && sharedNavigation.contains(key);
    }

    static Set<String> sharedNavigationKeys(List<List<DetectedElement>> pageElements) {
        if (pageElements.size() < 2) return Set.of();
        Map<String, Integer> occurrences = new HashMap<>();
        for (List<DetectedElement> elements : pageElements) {
            elements.stream().map(PlaywrightScreenAnalysisAdapter::navigationKey).filter(Objects::nonNull).distinct()
                    .forEach(key -> occurrences.merge(key, 1, Integer::sum));
        }
        return occurrences.entrySet().stream().filter(entry -> entry.getValue() == pageElements.size())
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    /** Named links, and named buttons inside a navigation landmark (e.g. a header "Sign out"), are global navigation candidates. */
    static String navigationKey(DetectedElement element) {
        if (element == null || element.accessibleName() == null || element.accessibleName().isBlank()) return null;
        boolean link = "a".equalsIgnoreCase(element.kind());
        boolean landmarkButton = "button".equalsIgnoreCase(element.kind()) && element.inNavigation();
        if (!link && !landmarkButton) return null;
        return (link ? "a" : "button") + "\u0000" + element.accessibleName().trim().toLowerCase(Locale.ROOT);
    }

    private ScreenAnalysisResult analyzeCurrentPage(com.microsoft.playwright.Page page, TargetApplication app, int depth) {
        waitForPageSettled(page);
        List<DetectedElement> found = new ArrayList<>();
        for (String kind : CAPTURED_KINDS) detect(page, page.locator(kind), kind, found, app, null);
        Locator sensitiveFields = page.locator(SENSITIVE_FIELD_SELECTOR); sensitiveFields.count();
        byte[] screenshot = page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setFullPage(true).setMask(List.of(sensitiveFields)));
        if (screenshot.length == 0) throw new IllegalStateException("Sanitized screenshot unavailable");
        return new ScreenAnalysisResult(safeUrl(page.url()), safe(page.title()), found, screenshot, List.of(), null, heading(page));
    }

    /** Bound for each opener press, Escape, or close-button press; the opener is discarded when it is reached. */
    static final double OPENER_ACTION_TIMEOUT_MS = 3_000;
    private static final String OPENER_CONTAINERS = "[role=dialog], [aria-modal=true], dialog[open]";
    private static final String INTERACTIVE_KINDS = "button, a, input, textarea, select";
    private static final List<String> CAPTURED_KINDS = List.of("button", "a", "input", "textarea", "select");
    private static final java.util.regex.Pattern CLOSE_BUTTON_NAME = java.util.regex.Pattern.compile("(?i)cancelar|cancel|cerrar|close");
    /** Page-side key of the pre-press snapshot; it lives only in the page's memory and disappears on reload. */
    private static final String OPENER_SNAPSHOT_KEY = "__systemGuideForgeOpenerSnapshot";
    private static final String VISIBLE_JS = "const visible = el => { if (!el.getClientRects().length) return false;"
            + "const style = getComputedStyle(el); return !el.hidden && style.display !== 'none' && style.visibility !== 'hidden'; };";
    /** Remembers which containers and controls were already visible before the press; reads the DOM only. */
    private static final String OPENER_SNAPSHOT_SCRIPT = "({ key, selector }) => {" + VISIBLE_JS
            + "window[key] = new WeakSet([...document.querySelectorAll(selector)].filter(visible)); return true; }";
    /** snapshot=false means the document was replaced (navigation or reload), so nothing can be compared. */
    private static final String OPENER_STATE_SCRIPT = "({ key, containers, interactive }) => {" + VISIBLE_JS
            + "const before = window[key]; const shown = [...document.querySelectorAll(containers)].filter(visible);"
            + "if (!before) return { snapshot: false, containers: 0, controls: 0, visibleContainers: shown.length };"
            + "return { snapshot: true, containers: shown.filter(el => !before.has(el)).length,"
            + "controls: [...document.querySelectorAll(interactive)].filter(el => visible(el) && !before.has(el)).length,"
            + "visibleContainers: shown.length }; }";
    /** Index of the outermost newly visible container among the matched elements, or -1. */
    private static final String NEW_CONTAINER_SCRIPT = "(all, key) => {" + VISIBLE_JS
            + "const before = window[key] || new WeakSet(); const fresh = all.filter(el => visible(el) && !before.has(el));"
            + "const outer = fresh.find(el => !fresh.some(other => other !== el && other.contains(el)));"
            + "return outer ? all.indexOf(outer) : -1; }";
    private static final String NEWLY_VISIBLE_SCRIPT = "(all, key) => {" + VISIBLE_JS
            + "const before = window[key] || new WeakSet(); return all.map(el => visible(el) && !before.has(el)); }";

    /**
     * Presses each human-approved opener button (RF24) on the current page, captures the form it reveals, and closes it
     * again. While an opener is active every non-GET/HEAD request is aborted before it leaves the browser; a blocked
     * request, WebSocket, popup, URL change, or failed close discards that opener's capture and restores the page.
     * Never fails the analysis, never enqueues links, and never counts as a crawled page.
     */
    private List<OpenedForm> openApprovedForms(com.microsoft.playwright.Page page, TargetApplication app, ApprovedOpenerMatcher matcher,
                                               List<DetectedElement> pageElements) {
        if (matcher == null) return List.of();
        String pageUrl = page.url();
        List<OpenedForm> opened = new ArrayList<>();
        Set<String> pressedNames = new HashSet<>();
        int buttonIndex = -1;
        for (DetectedElement element : pageElements) {
            if (!"button".equals(element.kind())) continue;
            buttonIndex++;
            String name = element.accessibleName();
            if (!matcher.matches(pageUrl, name) || pressedNames.contains(ApprovedOpenerMatcher.nameKey(name))) continue;
            try {
                // Re-located by index and re-checked by name, so a DOM that changed since detection never gets the press.
                Locator button = page.locator("button").nth(buttonIndex);
                if (!button.isVisible() || !button.isEnabled() || !ApprovedOpenerMatcher.nameKey(name).equals(ApprovedOpenerMatcher.nameKey(buttonName(button)))) continue;
                pressedNames.add(ApprovedOpenerMatcher.nameKey(name));
                OpenedForm form = openOne(page, app, button, name, element.selector());
                if (form != null) opened.add(form);
            } catch (RuntimeException e) {
                // Locating the opener or restoring the page failed, so the page state is unknown: stop pressing openers
                // here; the crawl itself continues.
                LOG.warn("Approved opener pass stopped: category=opener-pass-failed, exception={}", e.getClass().getName());
                break;
            }
        }
        return opened;
    }

    /** Null when nothing new appeared or the capture was discarded. Throws only when the page could not be restored. */
    private OpenedForm openOne(com.microsoft.playwright.Page page, TargetApplication app, Locator button, String name, String selector) {
        BrowserContext context = page.context();
        String originalUrl = page.url();
        AtomicReference<String> violation = new AtomicReference<>();
        Consumer<Route> guard = route -> {
            String method = route.request().method();
            if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) { route.resume(); return; }
            violation.compareAndSet(null, "write-request-blocked");
            route.abort();
        };
        Consumer<WebSocket> sockets = socket -> violation.compareAndSet(null, "websocket-opened");
        Consumer<com.microsoft.playwright.Page> popups = popup -> violation.compareAndSet(null, "popup-opened");
        context.route("**/*", guard);
        page.onWebSocket(sockets);
        context.onPage(popups);
        String discarded = null;
        OpenedForm form = null;
        try {
            int containersBefore = openerState(page).visibleContainers();
            page.evaluate(OPENER_SNAPSHOT_SCRIPT, Map.of("key", OPENER_SNAPSHOT_KEY, "selector", OPENER_CONTAINERS + ", " + INTERACTIVE_KINDS));
            button.click(new Locator.ClickOptions().setTimeout(OPENER_ACTION_TIMEOUT_MS));
            settleAfterOpenerAction(page);
            OpenerState state = openerState(page);
            if (violation.get() != null) discarded = violation.get();
            else if (!page.url().equals(originalUrl) || !state.snapshot()) discarded = "url-changed";
            else if (state.containers() == 0 && state.controls() == 0) return null;
            else {
                Locator container = newContainer(page);
                List<DetectedElement> elements = captureOpened(page, app, container);
                if (!closeOpened(page, app, container, containersBefore)) discarded = "close-failed";
                else if (violation.get() != null) discarded = violation.get();
                else if (!page.url().equals(originalUrl)) discarded = "url-changed";
                else if (!elements.isEmpty()) form = new OpenedForm(name, selector, elements);
            }
        } catch (RuntimeException e) {
            discarded = "opener-failed";
        } finally {
            try {
                if (discarded != null) {
                    LOG.info("Approved opener capture discarded: category={}", discarded);
                    for (com.microsoft.playwright.Page other : context.pages()) if (other != page) other.close();
                    page.navigate(originalUrl);
                    try { waitForPageSettled(page); } catch (RuntimeException e) { LOG.debug("Restored page did not settle: exception={}", e.getClass().getName()); }
                }
            } finally {
                page.offWebSocket(sockets);
                context.offPage(popups);
                context.unroute("**/*", guard);
            }
        }
        return discarded == null ? form : null;
    }

    private record OpenerState(boolean snapshot, int containers, int controls, int visibleContainers) {}

    private static OpenerState openerState(com.microsoft.playwright.Page page) {
        Object raw = page.evaluate(OPENER_STATE_SCRIPT, Map.of("key", OPENER_SNAPSHOT_KEY, "containers", OPENER_CONTAINERS, "interactive", INTERACTIVE_KINDS));
        if (!(raw instanceof Map<?, ?> state)) throw new IllegalStateException("Opener state unavailable");
        return new OpenerState(Boolean.TRUE.equals(state.get("snapshot")), number(state.get("containers")),
                number(state.get("controls")), number(state.get("visibleContainers")));
    }

    private static int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }

    /** The outermost newly visible dialog, or null when the opener only revealed controls in place (DOM diff mode). */
    private static Locator newContainer(com.microsoft.playwright.Page page) {
        Locator containers = page.locator(OPENER_CONTAINERS);
        int index = number(containers.evaluateAll(NEW_CONTAINER_SCRIPT, OPENER_SNAPSHOT_KEY));
        return index < 0 ? null : containers.nth(index);
    }

    /** Same detection, redaction, and classification as page elements; only reads attributes and text. */
    private List<DetectedElement> captureOpened(com.microsoft.playwright.Page page, TargetApplication app, Locator container) {
        List<DetectedElement> found = new ArrayList<>();
        for (String kind : CAPTURED_KINDS) {
            Locator locator = container != null ? container.locator(kind) : page.locator(kind);
            detect(page, locator, kind, found, app, container != null ? null : newlyVisible(locator));
        }
        return found;
    }

    private static List<Boolean> newlyVisible(Locator locator) {
        Object flags = locator.evaluateAll(NEWLY_VISIBLE_SCRIPT, OPENER_SNAPSHOT_KEY);
        return flags instanceof List<?> list ? list.stream().map(Boolean.TRUE::equals).toList() : List.of();
    }

    /** Escape, then a visible non-mutating close button inside the opened container, then a reload. */
    private boolean closeOpened(com.microsoft.playwright.Page page, TargetApplication app, Locator container, int containersBefore) {
        page.keyboard().press("Escape");
        settleAfterOpenerAction(page);
        if (isClosed(page, container != null, containersBefore)) return true;
        Locator closeButton = closeButton(page, container);
        if (closeButton != null) {
            closeButton.click(new Locator.ClickOptions().setTimeout(OPENER_ACTION_TIMEOUT_MS));
            settleAfterOpenerAction(page);
            if (isClosed(page, container != null, containersBefore)) return true;
        }
        page.reload(new com.microsoft.playwright.Page.ReloadOptions().setTimeout(PAGE_READY_TIMEOUT_MS));
        waitForPageSettled(page);
        return isClosed(page, container != null, containersBefore);
    }

    /** After a reload the snapshot is gone, so a closed state then means no more visible containers than before the press. */
    private static boolean isClosed(com.microsoft.playwright.Page page, boolean dialog, int containersBefore) {
        OpenerState state = openerState(page);
        if (!state.snapshot()) return state.visibleContainers() <= containersBefore;
        return dialog ? state.containers() == 0 : state.controls() == 0;
    }

    private static Locator closeButton(com.microsoft.playwright.Page page, Locator container) {
        Locator candidates = container != null ? container.locator("button[type=button i]") : page.locator("button[type=button i]");
        List<Boolean> include = container != null ? null : newlyVisible(candidates);
        int count = Math.min(candidates.count(), MAX_ELEMENTS_PER_KIND);
        for (int i = 0; i < count; i++) {
            if (include != null && (i >= include.size() || !include.get(i))) continue;
            Locator candidate = candidates.nth(i);
            String label = buttonName(candidate);
            if (!candidate.isVisible() || label == null || !CLOSE_BUTTON_NAME.matcher(label).matches()) continue;
            String pressLabel = first(candidate.getAttribute("aria-label"), text(candidate));
            ActionClassification classification = classifyFixtureElement(candidate, "button", "type", candidate.getAttribute("type"), pressLabel,
                    candidate.getAttribute("name"), candidate.getAttribute("id"), candidate.getAttribute("placeholder"), ariaRole(candidate.getAttribute("role")));
            if (classification != ActionClassification.MUTATING) return candidate;
        }
        return null;
    }

    /** A button's name as detect() computes it: label association first, then visible text, then placeholder. */
    private static String buttonName(Locator button) {
        return safe(first(humanLabel(button), text(button), button.getAttribute("placeholder")));
    }

    /** Waiting only and bounded; never fails the opener on its own. */
    private static void settleAfterOpenerAction(com.microsoft.playwright.Page page) {
        waitForNetworkIdle(page);
        waitForDomQuiet(page);
    }

    static final int MAX_HEADING_LENGTH = 255;

    /** Narrow heading policy: first rendered nonblank h1, then h2; no ARIA or deeper heading inference. */
    private static String heading(com.microsoft.playwright.Page page) {
        try {
            Object raw = page.evaluate("() => { for (const tag of ['h1', 'h2']) {"
                    + "for (const el of document.querySelectorAll(tag)) {"
                    + "if (!el.getClientRects().length) continue;"
                    + "const style = getComputedStyle(el);"
                    + "if (el.hidden || style.display === 'none' || style.visibility === 'hidden') continue;"
                    + "const text = el.innerText.trim(); if (text) return text;"
                    + "} } return null; }");
            if (!(raw instanceof String text)) return null;
            String normalized = text.trim().replaceAll("\\s+", " ");
            if (normalized.isEmpty()) return null;
            String redacted = safe(normalized);
            int end = redacted.offsetByCodePoints(0, Math.min(redacted.codePointCount(0, redacted.length()), MAX_HEADING_LENGTH));
            return redacted.substring(0, end).trim();
        } catch (RuntimeException e) {
            LOG.warn("Heading capture failed: exception={}", e.getClass().getName());
            return null;
        }
    }

    /** Overall readiness budget; exceeding it fails the analysis, as before route-data settling existed. */
    static final double PAGE_READY_TIMEOUT_MS = 10_000;
    /** Share of the readiness budget for the strict check before falling back to the original rule. */
    static final double STRICT_PAGE_READY_TIMEOUT_MS = 5_000;
    /** Upper bound for in-flight route data requests after readiness; reaching it continues the capture. */
    static final double NETWORK_IDLE_TIMEOUT_MS = 3_000;
    /** A DOM without mutations for this long is considered settled after route data rendered. */
    static final int DOM_QUIET_WINDOW_MS = 300;
    /** Cap for the DOM-quiet wait so polling or animated pages never block the capture; reaching it continues. */
    static final int DOM_QUIET_MAX_WAIT_MS = 2_000;

    /** Waiting only: readiness, then bounded route-data settling. Never clicks, types, or submits. */
    private void waitForPageSettled(com.microsoft.playwright.Page page) {
        waitForPageReady(page);
        waitForNetworkIdle(page);
        waitForDomQuiet(page);
    }

    /**
     * Readiness predicate. Strict mode ignores controls inside navigation landmarks (they render before route data)
     * and treats a visible [aria-busy=true] element as loading; lenient mode is the original rule.
     */
    private static final String PAGE_READY_SCRIPT = "({ navigationLandmarks, strict }) => {"
            + "if (document.readyState !== 'complete' || document.body === null) return false;"
            + "const text = document.body.innerText.trim();"
            + "if (!text) return false;"
            + "const visible = element => {"
            + "const style = window.getComputedStyle(element);"
            + "return !element.hidden && style.display !== 'none' && style.visibility !== 'hidden';"
            + "};"
            + "if (strict && [...document.querySelectorAll('[aria-busy=true]')].some(visible)) return false;"
            + "const controls = [...document.querySelectorAll('button, a, input, textarea, select, [role=button], [role=tab]')]"
            + ".filter(element => visible(element) && (!strict || element.closest(navigationLandmarks) === null)).length;"
            + "const hasLoadingState = /\\b(?:loading|please\\s+wait|initializing|fetching|updating)\\b/i.test(text);"
            + "const hasContentContainer = [...document.querySelectorAll('main, [role=main], article, section')]"
            + ".some(element => visible(element) && element.innerText.trim().length > 0);"
            + "return controls > 0 || (hasContentContainer && !hasLoadingState);"
            + "}";

    /**
     * Strict readiness first, bounded by STRICT_PAGE_READY_TIMEOUT_MS; if it never holds, the original rule gets the
     * rest of PAGE_READY_TIMEOUT_MS. The analysis fails only when the original rule would also have failed.
     */
    private void waitForPageReady(com.microsoft.playwright.Page page) {
        long started = System.nanoTime();
        try {
            waitForReadiness(page, true, STRICT_PAGE_READY_TIMEOUT_MS);
        } catch (TimeoutError e) {
            double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
            LOG.debug("Strict readiness not reached; falling back to the original readiness rule");
            waitForReadiness(page, false, Math.max(1, PAGE_READY_TIMEOUT_MS - elapsedMs));
        }
    }

    private static void waitForReadiness(com.microsoft.playwright.Page page, boolean strict, double timeoutMs) {
        page.waitForFunction(PAGE_READY_SCRIPT, Map.of("navigationLandmarks", NAVIGATION_LANDMARKS, "strict", strict),
                new Page.WaitForFunctionOptions().setTimeout(timeoutMs));
    }

    /** Language-independent: route data requests still in flight mean controls may not be rendered yet. */
    private static void waitForNetworkIdle(com.microsoft.playwright.Page page) {
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(NETWORK_IDLE_TIMEOUT_MS));
        } catch (TimeoutError e) {
            LOG.debug("Network idle not reached within settle budget; continuing capture");
        }
    }

    /** Resolves after a mutation-free window or at the cap, whichever comes first; never fails the capture. */
    private static final String DOM_QUIET_SCRIPT = "([quietMs, maxWaitMs]) => new Promise(resolve => {"
            + "let quietTimer; let capTimer;"
            + "const done = () => { observer.disconnect(); clearTimeout(quietTimer); clearTimeout(capTimer); resolve(true); };"
            + "const observer = new MutationObserver(() => { clearTimeout(quietTimer); quietTimer = setTimeout(done, quietMs); });"
            + "observer.observe(document, { subtree: true, childList: true, attributes: true, characterData: true });"
            + "quietTimer = setTimeout(done, quietMs); capTimer = setTimeout(done, maxWaitMs); })";

    private static void waitForDomQuiet(com.microsoft.playwright.Page page) {
        try {
            page.evaluate(DOM_QUIET_SCRIPT, List.of(DOM_QUIET_WINDOW_MS, DOM_QUIET_MAX_WAIT_MS));
        } catch (RuntimeException e) {
            LOG.debug("DOM quiet wait interrupted: exception={}; continuing capture", e.getClass().getName());
        }
    }

    static boolean hasRenderableContent(String readyState, String bodyText, int interactiveElementCount, boolean hasContentContainer) {
        return hasRenderableContent(readyState, bodyText, interactiveElementCount, hasContentContainer, false);
    }

    static boolean hasRenderableContent(String readyState, String bodyText, int interactiveElementCount,
                                        boolean hasContentContainer, boolean hasLoadingIndicator) {
        return "complete".equalsIgnoreCase(readyState)
                && bodyText != null && !bodyText.isBlank()
                && !hasLoadingIndicator
                && (interactiveElementCount > 0 || hasContentContainer);
    }

    private List<Link> links(com.microsoft.playwright.Page page, TargetApplication application, int depth, CrawlBudget budget) {
        List<Link> result = new ArrayList<>(); Locator locator = page.locator("a");
        for (int i = 0; i < Math.min(locator.count(), 500); i++) {
            if (!budget.takeLink()) break;
            Locator anchor = locator.nth(i);
            String href = anchor.getAttribute("href");
            String resolved = internalSafeUrl(href, page.url(), application.getBaseUrl());
            if (resolved != null && !application.isExcludedPath(resolved) && classifyResolvedAnchor(anchor.getAttribute("data-action-type"), resolved) == ActionClassification.SAFE) {
                result.add(new Link(resolved, depth));
            }
        }
        return result;
    }

    static ActionClassification classifyAnchor(String metadata, String href) {
        ActionClassification explicit = ActionClassifier.classifyFixtureMetadata(metadata);
        return explicit != null ? explicit : ActionClassifier.classify("a", "href", href);
    }

    static ActionClassification classifyResolvedAnchor(String metadata, String resolvedUrl) {
        ActionClassification explicit = ActionClassifier.classifyFixtureMetadata(metadata);
        if (explicit != null) return explicit;
        try { return ActionClassifier.classify("a", "href", URI.create(resolvedUrl).getPath()); }
        catch (RuntimeException e) { return ActionClassification.UNKNOWN; }
    }

    static boolean isLoginNavigationResult(String candidateUrl, TargetApplication application) {
        try {
            URI candidate = URI.create(candidateUrl), login = URI.create(application.getLoginUrl());
            return sameOrigin(candidate, login) && normalizedPath(candidate).equals(normalizedPath(login));
        } catch (RuntimeException e) { return false; }
    }

    static boolean isSafeNavigationResult(String candidateUrl, TargetApplication application) {
        return !isLoginNavigationResult(candidateUrl, application)
                && !application.isExcludedPath(candidateUrl)
                && internalSafeUrl(candidateUrl, candidateUrl, application.getBaseUrl()) != null;
    }

    static String internalSafeUrl(String href, String currentUrl, String baseUrl) {
        if (href == null || href.isBlank() || href.contains("%")) return null;
        try {
            URI raw = URI.create(href.trim());
            if ("javascript".equalsIgnoreCase(raw.getScheme()) || href.startsWith("//")) return null;
            URI candidate = new URI(new URI(currentUrl).resolve(raw).toString()); URI base = URI.create(baseUrl);
            if (candidate.toString().contains("%") || !sameOrigin(candidate, base)) return null;
            return safeUrl(candidate.toString());
        } catch (Exception e) { return null; }
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme() != null && left.getHost() != null && right.getScheme() != null && right.getHost() != null
                && Objects.equals(left.getScheme(), right.getScheme()) && Objects.equals(left.getHost(), right.getHost()) && left.getPort() == right.getPort();
    }
    private static String normalizedPath(URI uri) { String path = uri.getPath(); return path == null || path.isBlank() ? "/" : path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path; }
    /**
     * Narrow DOM label extraction: ignore descendant controls so their current/default values cannot become names,
     * and hidden descendants so text the user cannot see never reaches the manual. A referenced label root may itself
     * be hidden (aria-labelledby allows it), so only its descendants are filtered.
     */
    private static final String HUMAN_LABEL_SCRIPT = "el => {"
            + "const clean = root => { if (root.matches('input, textarea, select')) return '';"
            + "const rootHidden = getComputedStyle(root).visibility === 'hidden';"
            + "const collect = node => { let out = ''; for (const child of node.childNodes) {"
            + "if (child.nodeType === 3) { out += child.textContent || ''; continue; }"
            + "if (child.nodeType !== 1 || child.matches('input, textarea, select, script, style, template') || child.hidden || child.getAttribute('aria-hidden') === 'true') continue;"
            + "const style = getComputedStyle(child); if (style.display === 'none' || (!rootHidden && style.visibility === 'hidden')) continue;"
            + "const gap = style.display === 'inline' || style.display === 'contents' ? '' : ' '; out += gap + collect(child) + gap; } return out; };"
            + "return collect(root).replace(/\\s+/g, ' ').trim(); };"
            + "const refs = (el.getAttribute('aria-labelledby') || '').trim().split(/\\s+/).filter(Boolean);"
            + "const referenced = refs.map(id => document.getElementById(id)).filter(Boolean).map(clean).filter(Boolean).join(' ');"
            + "if (referenced) return referenced;"
            + "const aria = (el.getAttribute('aria-label') || '').trim(); if (aria) return aria;"
            + "const labels = el.labels ? [...el.labels].map(clean).filter(Boolean).join(' ') : ''; return labels || null; }";

    private static String humanLabel(Locator item) {
        try { Object label = item.evaluate(HUMAN_LABEL_SCRIPT); return label instanceof String value ? first(value) : null; }
        catch (RuntimeException e) { return null; }
    }

    /** locator holds the elements of one kind (page-wide or inside an opened container); include, when given, keeps only flagged indices. */
    private void detect(com.microsoft.playwright.Page page, Locator locator, String kind, List<DetectedElement> found, TargetApplication app, List<Boolean> include) {
        int count = Math.min(locator.count(), MAX_ELEMENTS_PER_KIND);
        List<Boolean> dataItems = "button".equals(kind) ? dataItems(locator) : List.of();
        for (int i = 0; i < count; i++) { if (include != null && (i >= include.size() || !include.get(i))) continue;
            Locator item = locator.nth(i); String selector = kind + ":nth-of-type(" + (i + 1) + ")";
            String aria=item.getAttribute("aria-label"), name=item.getAttribute("name"), id=item.getAttribute("id"), placeholder=item.getAttribute("placeholder"), type=item.getAttribute("type"), href=item.getAttribute("href");
            // A select's visible text is its options (record data such as user names), so it is never read as its name.
            String visibleText=("input".equals(kind)||"textarea".equals(kind)||"select".equals(kind))?null:text(item); String buttonValue="input".equals(kind)&&type!=null&&type.trim().matches("(?i)submit|button|reset")?item.getAttribute("value"):null; String label=first(humanLabel(item),visibleText,buttonValue,placeholder); String pressLabel=first(aria,visibleText,item.getAttribute("value")); String attribute="a".equals(kind)?"href":"type"; String value="a".equals(kind)?href:type; String role="button".equals(kind)?ariaRole(item.getAttribute("role")):null;
            boolean dataItem = i < dataItems.size() && Boolean.TRUE.equals(dataItems.get(i));
            // A data item's name is record data (a task title), not an action label, so it never drives classification.
            String classifiedLabel = dataItem ? null : pressLabel;
            found.add(new DetectedElement(kind,selector,safe(label),classifyFixtureElement(item,kind,attribute,value,classifiedLabel,name,id,placeholder,role),"a".equals(kind)?targetPath(href,page.url(),app.getBaseUrl()):null,("a".equals(kind)||"button".equals(kind))&&inNavigation(item),"input".equals(kind)?controlType(type):"tab".equals(role)?"tab":dataItem?"item":null)); }
    }
    /**
     * Read-only structural check, aligned with the locator's matches: a button is a repeated data item (a board card or
     * list entry whose name is record data) when its nearest repeating ancestor (a sibling group sharing tag and a class)
     * holds exactly one button at the same relative path, the same structural signature appears at least twice
     * page-wide, and every name in that signature is distinct and non-empty. At least one unit of that signature must
     * also carry text beyond its button (a title plus assignee or priority); title-only cards then join it, while a
     * structure whose units all hold only a button is indistinguishable from a toolbar. Names that all share their first
     * word ("Eliminar Juan", "Eliminar Ana") are one action per record, so that structure is not a data-item group. Per-row actions with identical names ("Desactivar" on each row), buttons inside navigation
     * landmarks, toolbars or menus, tabs, and buttons that submit (explicit type=submit, or type-less inside a form)
     * are never data items.
     */
    private static final String DATA_ITEMS_SCRIPT = """
            (all, { landmarks, limit }) => {
              // Same cap as detect(), so flag i still describes locator.nth(i).
              const buttons = all.slice(0, limit);
              const eligible = b => !b.closest(landmarks + ', [role=toolbar], [role=menu], [role=menubar]')
                && (b.getAttribute('role') || '').trim().split(/\\s+/)[0].toLowerCase() !== 'tab'
                && (b.getAttribute('type') || '').trim().toLowerCase() !== 'submit'
                && !(b.form && (b.type || '').toLowerCase() === 'submit');
              const textOf = n => (n.textContent || '').replace(/\\s+/g, ' ').trim();
              const hasContent = (unit, b) => textOf(unit).length > textOf(b).length;
              const nameOf = b => (b.getAttribute('aria-label') || b.textContent || '').replace(/\\s+/g, ' ').trim().toLowerCase();
              const alike = (a, b) => a.tagName === b.tagName
                && ((a.classList.length === 0 && b.classList.length === 0) || [...a.classList].some(c => b.classList.contains(c)));
              const signature = (unit, b) => { const steps = []; for (let n = b; n && n !== unit; n = n.parentElement) steps.unshift(n.tagName);
                return unit.parentElement.tagName + '>' + unit.tagName + '>' + steps.join('>'); };
              const buttonsIn = unit => unit.querySelectorAll('button');
              const ancestors = b => { const out = []; for (let n = b.parentElement, d = 0; n && n.parentElement && n !== document.body && d < 8; n = n.parentElement, d++) out.push(n); return out; };
              const repeating = new Map(); const withContent = new Set(); const assigned = buttons.map(() => null);
              buttons.forEach((b, i) => { if (!eligible(b)) return;
                for (const unit of ancestors(b)) {
                  const members = [...unit.parentElement.children].filter(s => alike(s, unit) && buttonsIn(s).length > 0);
                  if (members.length < 2) continue;
                  const sig = signature(unit, b);
                  if (members.every(s => buttonsIn(s).length === 1 && signature(s, buttonsIn(s)[0]) === sig)) { if (!repeating.has(sig)) repeating.set(sig, []); repeating.get(sig).push(unit); assigned[i] = sig;
                    if (hasContent(unit, b)) withContent.add(sig); }
                  return;
                } });
              // A lone card (one card left in a column) joins a signature that repeats elsewhere on the page, but only when it
              // and its container look like one of those repeated cards; a same-tag-path button elsewhere stays ordinary.
              const matchesPeer = (unit, peers) => peers.some(u => alike(u, unit) && alike(u.parentElement, unit.parentElement));
              buttons.forEach((b, i) => { if (assigned[i] !== null || !eligible(b)) return;
                for (const unit of ancestors(b)) { if (buttonsIn(unit).length !== 1) return; const sig = signature(unit, b);
                  if (repeating.has(sig)) { if (matchesPeer(unit, repeating.get(sig))) { assigned[i] = sig; if (hasContent(unit, b)) withContent.add(sig); } return; } } });
              const names = new Map();
              assigned.forEach((sig, i) => { if (sig !== null) { if (!names.has(sig)) names.set(sig, []); names.get(sig).push(nameOf(buttons[i])); } });
              // Content is judged per structure: optional fields (assignee, badges) may be absent on some cards.
              // Names that all open with the same word ("Eliminar Juan", "Eliminar Ana") are one action per record, not data.
              const sameVerb = list => new Set(list.map(n => n.split(' ')[0])).size === 1;
              const valid = new Set([...names].filter(([sig, list]) => withContent.has(sig) && !sameVerb(list) && list.length >= 2 && list.every(n => n) && new Set(list).size === list.length).map(([sig]) => sig));
              return assigned.map(sig => sig !== null && valid.has(sig));
            }""";
    /** Package-private so the failure fallback can be exercised without a browser fault. */
    static List<Boolean> dataItems(Locator buttons) {
        try { Object result = buttons.evaluateAll(DATA_ITEMS_SCRIPT, Map.of("landmarks", NAVIGATION_LANDMARKS, "limit", MAX_ELEMENTS_PER_KIND));
            return result instanceof List<?> flags ? flags.stream().map(Boolean.TRUE::equals).toList() : List.of(); }
        catch (RuntimeException e) {
            // Detection falls back to ordinary buttons; only the exception type is logged, never page content.
            LOG.warn("Data-item detection failed; treating buttons as ordinary: exception={}", e.getClass().getName());
            return List.of();
        }
    }
    /** The first token of an ARIA role attribute (browsers use the first recognized one), normalized; only the attribute is read. */
    static String ariaRole(String role) {
        if (role == null || role.isBlank()) return null;
        return role.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
    }
    /** The input's type attribute as browsers interpret it: missing or unrecognizable values fall back to "text". Only the type is read, never the value. */
    static String controlType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z-]{1,32}") ? normalized : "text";
    }
    private static final String NAVIGATION_LANDMARKS = "nav, header, aside, [role=navigation], [role=banner]";
    private static boolean inNavigation(Locator item){try{return Boolean.TRUE.equals(item.evaluate("(el, selector) => el.closest(selector) !== null", NAVIGATION_LANDMARKS));}catch(Exception e){return false;}}
    /** Same-origin path of an anchor (no query or fragment, via the same sanitizing as crawled links); fragment-only hrefs point nowhere new. */
    static String targetPath(String href, String currentUrl, String baseUrl) {
        if (href == null || href.isBlank() || href.trim().startsWith("#")) return null;
        String resolved = internalSafeUrl(href, currentUrl, baseUrl);
        if (resolved == null) return null;
        try { String path = normalizedPath(URI.create(resolved)); return path.length() <= 255 ? path : null; } catch (RuntimeException e) { return null; }
    }
    private static ActionClassification classifyFixtureElement(Locator item,String kind,String attribute,String value,String pressLabel,String name,String id,String placeholder,String role){ if(isSensitiveField(kind,value,name,id,placeholder))return ActionClassification.UNKNOWN; ActionClassification metadata=ActionClassifier.classifyFixtureMetadata(item.getAttribute("data-action-type")); if(metadata!=null)return metadata; return "a".equals(kind)?ActionClassifier.classify(kind,attribute,value):ActionClassifier.classifyControl(kind,value,pressLabel,role); }
    private static boolean isSensitiveField(String kind,String type,String name,String id,String placeholder){if(!"input".equals(kind)&&!"textarea".equals(kind))return false; return String.join(" ",type==null?"":type,name==null?"":name,id==null?"":id,placeholder==null?"":placeholder).matches("(?i).*(password|api[-_]?key|secret|token|credential).*");}
    private static final String VISIBLE_TEXT_SCRIPT = "root => { const collect = el => { let out = ''; for (const child of el.childNodes) { if (child.nodeType === 3) out += child.textContent || ''; else if (child.nodeType === 1 && child.getAttribute('aria-hidden') !== 'true') { const display = getComputedStyle(child).display; if (display === 'none') continue; const gap = display === 'inline' || display === 'contents' ? '' : ' '; out += gap + collect(child) + gap; } } return out; }; return collect(root).replace(/\\s+/g, ' ').trim(); }"; private static String text(Locator l){try{Object value=l.evaluate(VISIBLE_TEXT_SCRIPT);return value instanceof String visible?visible:null;}catch(Exception e){return null;}} private static String first(String... values){for(String v:values)if(v!=null&&!v.isBlank())return v.trim();return null;} private static String safe(String value){return value==null?null:value.replaceAll("(?i)password|secret|credential|token|api[-_]?key","[redacted]");} private static String safeUrl(String value){try{URI uri=URI.create(value);return new URI(uri.getScheme(),uri.getAuthority(),normalizedPath(uri),null,null).toString();}catch(Exception e){return "about:blank";}}
    static final class CrawlBudget {
        private int pages;
        private int links;
        CrawlBudget(int pages, int links) { this.pages = pages; this.links = links; }
        boolean takePage() { if (pages == 0) return false; pages--; return true; }
        boolean takeLink() { if (links == 0) return false; links--; return true; }
    }
    private record Link(String url,int depth) {}
    @FunctionalInterface interface BrowserFactory { Playwright create(); }
    @FunctionalInterface interface LoginCapture { ScreenAnalysisResult capture(com.microsoft.playwright.Page page, TargetApplication application); }
}
