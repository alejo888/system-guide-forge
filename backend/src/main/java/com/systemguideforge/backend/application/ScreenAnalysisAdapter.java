package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.TargetApplication;
import java.util.List;

public interface ScreenAnalysisAdapter {
    ScreenAnalysisResult analyze(TargetApplication application, String username, String password);

    record ScreenAnalysisResult(String url, String title, List<DetectedElement> elements,
                                byte[] sanitizedScreenshot, List<DiscoveredPage> discoveredPages, LoginPage loginPage) {
        public ScreenAnalysisResult(String url, String title, List<DetectedElement> elements,
                                     byte[] sanitizedScreenshot, List<DiscoveredPage> discoveredPages) {
            this(url, title, elements, sanitizedScreenshot, discoveredPages, null);
        }
        public ScreenAnalysisResult(String url, String title, List<DetectedElement> elements, byte[] sanitizedScreenshot) {
            this(url, title, elements, sanitizedScreenshot, List.of());
        }
    }

    record DiscoveredPage(String url, String title, List<DetectedElement> elements,
                          byte[] sanitizedScreenshot, int depth, ActionClassification classification) {}

    /** The target's login page, captured with an empty form before credentials are typed. */
    record LoginPage(String url, String title, List<DetectedElement> elements, byte[] sanitizedScreenshot) {}

    record DetectedElement(String kind, String selector, String accessibleName, ActionClassification classification) {}
}
