package com.systemguideforge.backend.application;

import com.microsoft.playwright.*;
import com.systemguideforge.backend.persistence.TargetApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.*;
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
             BrowserContext context = browser.newContext()) {
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

            String startUrl = safeUrl(page.url());
            CrawlBudget budget = new CrawlBudget(AnalysisService.MAX_CRAWL_PAGES, AnalysisService.MAX_CRAWL_LINKS);
            if (!budget.takePage()) throw new IllegalStateException("Crawl page budget unavailable");
            ScreenAnalysisResult first = analyzeCurrentPage(page, application, 0);
            List<DiscoveredPage> discovered = new ArrayList<>();
            Set<String> visited = new HashSet<>(Set.of(startUrl));
            ArrayDeque<Link> queue = new ArrayDeque<>(links(page, application, 1, budget));
            while (!queue.isEmpty()) {
                Link link = queue.removeFirst();
                if (link.depth > application.getMaxCrawlDepth() || application.isExcludedPath(link.url) || !visited.add(link.url) || !budget.takePage()) continue;
                page.navigate(link.url);
                if (isLoginNavigationResult(page.url(), application)) continue;
                if (!isSafeNavigationResult(page.url(), application)) {
                    throw new IllegalStateException("Unsafe navigation redirect rejected");
                }
                ScreenAnalysisResult current = analyzeCurrentPage(page, application, link.depth);
                discovered.add(new DiscoveredPage(current.url(), current.title(), current.elements(), current.sanitizedScreenshot(), link.depth, ActionClassification.SAFE, current.heading()));
                if (link.depth < application.getMaxCrawlDepth()) queue.addAll(links(page, application, link.depth + 1, budget));
            }
            ScreenAnalysisResult combined = withSharedNavigationIncludedOnce(first, discovered);
            return new ScreenAnalysisResult(combined.url(), combined.title(), combined.elements(), combined.sanitizedScreenshot(), combined.discoveredPages(), loginPage, combined.heading());
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
        if (sharedNavigation.isEmpty()) return new ScreenAnalysisResult(first.url(), first.title(), first.elements(), first.sanitizedScreenshot(), discovered, null, first.heading());
        List<DiscoveredPage> withoutRepeatedNavigation = discovered.stream()
                .map(page -> new DiscoveredPage(page.url(), page.title(),
                        page.elements().stream().filter(element -> !isSharedNavigation(element, sharedNavigation)).toList(),
                        page.sanitizedScreenshot(), page.depth(), ActionClassification.SAFE, page.heading()))
                .toList();
        return new ScreenAnalysisResult(first.url(), first.title(), first.elements(), first.sanitizedScreenshot(), withoutRepeatedNavigation, null, first.heading());
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
        detect(page, "button", found, app); detect(page, "a", found, app); detect(page, "input", found, app); detect(page, "textarea", found, app);
        Locator sensitiveFields = page.locator(SENSITIVE_FIELD_SELECTOR); sensitiveFields.count();
        byte[] screenshot = page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setFullPage(true).setMask(List.of(sensitiveFields)));
        if (screenshot.length == 0) throw new IllegalStateException("Sanitized screenshot unavailable");
        return new ScreenAnalysisResult(safeUrl(page.url()), safe(page.title()), found, screenshot, List.of(), null, heading(page));
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

    private void detect(com.microsoft.playwright.Page page, String kind, List<DetectedElement> found, TargetApplication app) {
        Locator locator = page.locator(kind); int count = Math.min(locator.count(), 500);
        for (int i = 0; i < count; i++) { Locator item = locator.nth(i); String selector = kind + ":nth-of-type(" + (i + 1) + ")";
            String aria=item.getAttribute("aria-label"), name=item.getAttribute("name"), id=item.getAttribute("id"), placeholder=item.getAttribute("placeholder"), type=item.getAttribute("type"), href=item.getAttribute("href");
            String visibleText=("input".equals(kind)||"textarea".equals(kind))?null:text(item); String buttonValue="input".equals(kind)&&type!=null&&type.trim().matches("(?i)submit|button|reset")?item.getAttribute("value"):null; String label=first(humanLabel(item),visibleText,buttonValue,placeholder); String pressLabel=first(aria,visibleText,item.getAttribute("value")); String attribute="a".equals(kind)?"href":"type"; String value="a".equals(kind)?href:type; String role="button".equals(kind)?ariaRole(item.getAttribute("role")):null;
            found.add(new DetectedElement(kind,selector,safe(label),classifyFixtureElement(item,kind,attribute,value,pressLabel,name,id,placeholder,role),"a".equals(kind)?targetPath(href,page.url(),app.getBaseUrl()):null,("a".equals(kind)||"button".equals(kind))&&inNavigation(item),"input".equals(kind)?controlType(type):"tab".equals(role)?"tab":null)); }
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
