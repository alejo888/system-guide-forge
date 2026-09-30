package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.TargetApplication;
import java.util.List;

public interface ScreenAnalysisAdapter {
    ScreenAnalysisResult analyze(TargetApplication application, String username, String password);

    /** heading is the first visible h1 (sanitized, nullable); title stays the raw document title. */
    record ScreenAnalysisResult(String url, String title, List<DetectedElement> elements,
                                byte[] sanitizedScreenshot, List<DiscoveredPage> discoveredPages, LoginPage loginPage, String heading) {
        public ScreenAnalysisResult(String url, String title, List<DetectedElement> elements,
                                     byte[] sanitizedScreenshot, List<DiscoveredPage> discoveredPages, LoginPage loginPage) {
            this(url, title, elements, sanitizedScreenshot, discoveredPages, loginPage, null);
        }
        public ScreenAnalysisResult(String url, String title, List<DetectedElement> elements,
                                     byte[] sanitizedScreenshot, List<DiscoveredPage> discoveredPages) {
            this(url, title, elements, sanitizedScreenshot, discoveredPages, null);
        }
        public ScreenAnalysisResult(String url, String title, List<DetectedElement> elements, byte[] sanitizedScreenshot) {
            this(url, title, elements, sanitizedScreenshot, List.of());
        }
    }

    record DiscoveredPage(String url, String title, List<DetectedElement> elements,
                          byte[] sanitizedScreenshot, int depth, ActionClassification classification, String heading) {
        public DiscoveredPage(String url, String title, List<DetectedElement> elements,
                              byte[] sanitizedScreenshot, int depth, ActionClassification classification) {
            this(url, title, elements, sanitizedScreenshot, depth, classification, null);
        }
    }

    /** The target's login page, captured with an empty form before credentials are typed.
     * The role labels are the associated control names (never field values); see PlaywrightScreenAnalysisAdapter. */
    record LoginPage(String url, String title, List<DetectedElement> elements, byte[] sanitizedScreenshot,
                      String usernameLabel, String passwordLabel, String submitLabel, String heading) {
        public LoginPage(String url, String title, List<DetectedElement> elements, byte[] sanitizedScreenshot,
                          String usernameLabel, String passwordLabel, String submitLabel) {
            this(url, title, elements, sanitizedScreenshot, usernameLabel, passwordLabel, submitLabel, null);
        }
        public LoginPage(String url, String title, List<DetectedElement> elements, byte[] sanitizedScreenshot) {
            this(url, title, elements, sanitizedScreenshot, null, null, null, null);
        }
    }

    /** targetPath is the sanitized same-origin path of an anchor (never a query, fragment or credentials); null otherwise. */
    record DetectedElement(String kind, String selector, String accessibleName, ActionClassification classification, String targetPath) {
        public DetectedElement(String kind, String selector, String accessibleName, ActionClassification classification) {
            this(kind, selector, accessibleName, classification, null);
        }
    }
}
