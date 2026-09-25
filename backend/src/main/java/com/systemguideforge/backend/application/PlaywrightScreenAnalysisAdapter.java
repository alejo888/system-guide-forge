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
                discovered.add(new DiscoveredPage(current.url(), current.title(), current.elements(), current.sanitizedScreenshot(), link.depth, ActionClassification.SAFE));
                if (link.depth < application.getMaxCrawlDepth()) queue.addAll(links(page, application, link.depth + 1, budget));
            }
            return withSharedNavigationIncludedOnce(first, discovered);
        } catch (Exception e) {
            String category = failureCategory(e);
            LOG.warn("Screen analysis failed: category={}, exception={}, origin={}", category, e.getClass().getName(), failureOrigin(e));
            throw new IllegalStateException("Screen analysis unavailable or failed: " + category);
        }
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
        if (sharedNavigation.isEmpty()) return new ScreenAnalysisResult(first.url(), first.title(), first.elements(), first.sanitizedScreenshot(), discovered);
        List<DiscoveredPage> withoutRepeatedNavigation = discovered.stream()
                .map(page -> new DiscoveredPage(page.url(), page.title(),
                        page.elements().stream().filter(element -> !isSharedNavigation(element, sharedNavigation)).toList(),
                        page.sanitizedScreenshot(), page.depth(), ActionClassification.SAFE))
                .toList();
        return new ScreenAnalysisResult(first.url(), first.title(), first.elements(), first.sanitizedScreenshot(), withoutRepeatedNavigation);
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

    static String navigationKey(DetectedElement element) {
        if (element == null || !"a".equalsIgnoreCase(element.kind()) || element.accessibleName() == null || element.accessibleName().isBlank()) return null;
        return "a\u0000" + element.accessibleName().trim().toLowerCase(Locale.ROOT);
    }

    private ScreenAnalysisResult analyzeCurrentPage(com.microsoft.playwright.Page page, TargetApplication app, int depth) {
        waitForPageReady(page);
        List<DetectedElement> found = new ArrayList<>();
        detect(page, "button", found); detect(page, "a", found); detect(page, "input", found); detect(page, "textarea", found);
        Locator sensitiveFields = page.locator(SENSITIVE_FIELD_SELECTOR); sensitiveFields.count();
        byte[] screenshot = page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setFullPage(true).setMask(List.of(sensitiveFields)));
        if (screenshot.length == 0) throw new IllegalStateException("Sanitized screenshot unavailable");
        return new ScreenAnalysisResult(safeUrl(page.url()), safe(page.title()), found, screenshot);
    }

    private void waitForPageReady(com.microsoft.playwright.Page page) {
        page.waitForFunction("() => {"
                        + "if (document.readyState !== 'complete' || document.body === null) return false;"
                        + "const text = document.body.innerText.trim();"
                        + "if (!text) return false;"
                        + "const visible = element => {"
                        + "const style = window.getComputedStyle(element);"
                        + "return !element.hidden && style.display !== 'none' && style.visibility !== 'hidden';"
                        + "};"
                        + "const controls = [...document.querySelectorAll('button, a, input, textarea, select, [role=button], [role=tab]')]"
                        + ".filter(visible).length;"
                        + "const hasLoadingState = /\\b(?:loading|please\\s+wait|initializing|fetching|updating)\\b/i.test(text);"
                        + "const hasContentContainer = [...document.querySelectorAll('main, [role=main], article, section')]"
                        + ".some(element => visible(element) && element.innerText.trim().length > 0);"
                        + "return controls > 0 || (hasContentContainer && !hasLoadingState);"
                        + "}",
                null, new Page.WaitForFunctionOptions().setTimeout(10_000));
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
    private void detect(com.microsoft.playwright.Page page, String kind, List<DetectedElement> found) {
        Locator locator = page.locator(kind); int count = Math.min(locator.count(), 500);
        for (int i = 0; i < count; i++) { Locator item = locator.nth(i); String selector = kind + ":nth-of-type(" + (i + 1) + ")";
            String aria=item.getAttribute("aria-label"), name=item.getAttribute("name"), id=item.getAttribute("id"), placeholder=item.getAttribute("placeholder"), type=item.getAttribute("type"), href=item.getAttribute("href");
            String label=first(aria,name,placeholder,text(item)); String attribute="a".equals(kind)?"href":"type"; String value="a".equals(kind)?href:type;
            found.add(new DetectedElement(kind,selector,safe(label),classifyFixtureElement(item,kind,attribute,value,name,id,placeholder))); }
    }
    private static ActionClassification classifyFixtureElement(Locator item,String kind,String attribute,String value,String name,String id,String placeholder){ if(isSensitiveField(kind,value,name,id,placeholder))return ActionClassification.UNKNOWN; ActionClassification metadata=ActionClassifier.classifyFixtureMetadata(item.getAttribute("data-action-type")); return metadata!=null?metadata:ActionClassifier.classify(kind,attribute,value); }
    private static boolean isSensitiveField(String kind,String type,String name,String id,String placeholder){if(!"input".equals(kind)&&!"textarea".equals(kind))return false; return String.join(" ",type==null?"":type,name==null?"":name,id==null?"":id,placeholder==null?"":placeholder).matches("(?i).*(password|api[-_]?key|secret|token|credential).*");}
    private static String text(Locator l){try{return l.textContent();}catch(Exception e){return null;}} private static String first(String... values){for(String v:values)if(v!=null&&!v.isBlank())return v.trim();return null;} private static String safe(String value){return value==null?null:value.replaceAll("(?i)password|secret|credential|token|api[-_]?key","[redacted]");} private static String safeUrl(String value){try{URI uri=URI.create(value);return new URI(uri.getScheme(),uri.getAuthority(),normalizedPath(uri),null,null).toString();}catch(Exception e){return "about:blank";}}
    static final class CrawlBudget {
        private int pages;
        private int links;
        CrawlBudget(int pages, int links) { this.pages = pages; this.links = links; }
        boolean takePage() { if (pages == 0) return false; pages--; return true; }
        boolean takeLink() { if (links == 0) return false; links--; return true; }
    }
    private record Link(String url,int depth) {}
    @FunctionalInterface interface BrowserFactory { Playwright create(); }
}
