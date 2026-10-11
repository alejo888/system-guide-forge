package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AnalysisService {
    private static final Logger LOG = LoggerFactory.getLogger(AnalysisService.class);
    public static final int MAX_ACTIVE_ANALYSES = 1;
    public static final int MAX_CRAWL_DEPTH = 5;
    public static final int MAX_CRAWL_PAGES = 100;
    public static final int MAX_CRAWL_LINKS = 500;
    private final AnalysisRepository analyses; private final PageRepository pages; private final UIElementRepository elements; private final ScreenshotRepository screenshots; private final TargetApplicationRepository applications; private final CredentialProtector protector; private final ScreenAnalysisAdapter adapter; private final DocumentRepository documents; private final DocumentSectionRepository sections;
    public AnalysisService(AnalysisRepository analyses,PageRepository pages,UIElementRepository elements,ScreenshotRepository screenshots,TargetApplicationRepository applications,CredentialProtector protector,ScreenAnalysisAdapter adapter){this(analyses, pages, elements, screenshots, applications, protector, adapter, null, null);}
    @Autowired public AnalysisService(AnalysisRepository analyses,PageRepository pages,UIElementRepository elements,ScreenshotRepository screenshots,TargetApplicationRepository applications,CredentialProtector protector,ScreenAnalysisAdapter adapter,DocumentRepository documents,DocumentSectionRepository sections){this.analyses=analyses;this.pages=pages;this.elements=elements;this.screenshots=screenshots;this.applications=applications;this.protector=protector;this.adapter=adapter;this.documents=documents;this.sections=sections;}
    public synchronized Analysis start(String applicationId){
        if (MAX_ACTIVE_ANALYSES == 1 && analyses.existsByStatusIn(List.of(AnalysisStatus.RUNNING))) throw new AnalysisInProgressException();
        TargetApplication app=applications.findById(applicationId).orElseThrow(); Analysis analysis=analyses.saveAndFlush(new Analysis(applicationId));
        try { ScreenAnalysisAdapter.ScreenAnalysisResult result=adapter.analyze(app,protector.decrypt(app.getUsernameEncrypted()),protector.decrypt(app.getPasswordEncrypted()));
            // The login page is a pre-authentication state, so it never deduplicates authenticated pages sharing its URL.
            // It still counts toward MAX_CRAWL_PAGES by design: the cap bounds every persisted page of an analysis.
            int persistedPages = 0;
            if (result.loginPage() != null && persistLoginPage(analysis, result.loginPage())) persistedPages++;
            Set<String> visited = new HashSet<>();
            if (persistPage(analysis, result.url(), result.title(), result.heading(), result.elements(), result.openedForms(), result.sanitizedScreenshot(), visited, null)) persistedPages++;
            int maxCrawlDepth = Math.min(app.getMaxCrawlDepth(), MAX_CRAWL_DEPTH);
            for (var discovered : result.discoveredPages()) {
                if (persistedPages >= MAX_CRAWL_PAGES) break;
                if (discovered.classification() == ActionClassification.SAFE && discovered.depth() <= maxCrawlDepth
                        && persistPage(analysis, discovered.url(), discovered.title(), discovered.heading(), discovered.elements(), discovered.openedForms(), discovered.sanitizedScreenshot(), visited, null)) persistedPages++;
            }
            analysis.complete(); return analyses.save(analysis);
        } catch(Exception ex){analysis.fail(ex.getMessage()); return analyses.save(analysis);}
    }
    private boolean persistPage(Analysis analysis, String url, String title, String heading, List<ScreenAnalysisAdapter.DetectedElement> detected, List<ScreenAnalysisAdapter.OpenedForm> openedForms, byte[] screenshot, Set<String> visited, PageKind kind) {
        if (!visited.add(url)) return false;
        Page page=pages.save(new Page(analysis.getId(),url,title,kind,null,null,null,heading));
        List<UIElement> pageElements = new ArrayList<>();
        for(var e:detected) pageElements.add(saveElement(page, e, null));
        for (var form : openedForms) {
            // The opener must be a button persisted on this same page; otherwise the form has nothing to link to.
            Optional<UIElement> opener = pageElements.stream()
                    .filter(element -> "button".equals(element.getKind()) && Objects.equals(element.getSelector(), form.openerSelector()))
                    .findFirst();
            if (opener.isEmpty()) { LOG.warn("Opened form skipped: category=opener-not-persisted"); continue; }
            for (var e : form.elements()) saveElement(page, e, opener.get().getId());
        }
        if(screenshot!=null) screenshots.save(new Screenshot(page.getId(),screenshot));
        return true;
    }
    /** Saves one captured element; the entity owns its id, so callers can link to it even when the repository returns another instance. */
    private UIElement saveElement(Page page, ScreenAnalysisAdapter.DetectedElement e, String openerElementId) {
        UIElement element = new UIElement(page.getId(), e.kind(), e.selector(), e.accessibleName(), e.classification(), e.targetPath(), e.inNavigation(), e.controlType(), openerElementId);
        elements.save(element);
        return element;
    }
    /** The login page always persists once (its own dedup set), and carries its captured role labels. */
    private boolean persistLoginPage(Analysis analysis, ScreenAnalysisAdapter.LoginPage loginPage) {
        Page page = pages.save(new Page(analysis.getId(), loginPage.url(), loginPage.title(), PageKind.LOGIN,
                loginPage.usernameLabel(), loginPage.passwordLabel(), loginPage.submitLabel(), loginPage.heading()));
        for (var e : loginPage.elements()) elements.save(new UIElement(page.getId(), e.kind(), e.selector(), e.accessibleName(), e.classification(), e.targetPath(), e.inNavigation(), e.controlType()));
        if (loginPage.sanitizedScreenshot() != null) screenshots.save(new Screenshot(page.getId(), loginPage.sanitizedScreenshot()));
        return true;
    }
    @Transactional public UIElement setManualInclusionApproval(String elementId, boolean approved) {
        if (documents == null || sections == null) throw new IllegalStateException("Manual inclusion requires document cache management");
        UIElement element = elements.findById(elementId).orElseThrow(java.util.NoSuchElementException::new);
        boolean changed = element.isManualInclusionApproved() != approved;
        try { element.setManualInclusionApproved(approved); }
        catch (IllegalStateException e) { throw new InvalidManualInclusionApprovalException(); }
        if (changed) {
            Page page = pages.findById(element.getPageId()).orElseThrow(java.util.NoSuchElementException::new);
            documents.findBySourceAnalysisId(page.getAnalysisId()).ifPresent(document -> {
                sections.deleteAll(sections.findByDocumentIdOrderByPositionAsc(document.getId()));
                documents.delete(document);
            });
        }
        return elements.save(element);
    }
    public Optional<Analysis> get(String id){return analyses.findById(id);}
    public List<Page> pages(String id){analyses.findById(id).orElseThrow(java.util.NoSuchElementException::new); return pages.findByAnalysisId(id).stream().sorted(Comparator.comparingInt(page -> page.getKind() == PageKind.LOGIN ? 0 : 1)).toList();}
    public List<FunctionalModuleDeriver.Module> modules(String id){ analyses.findById(id).orElseThrow(java.util.NoSuchElementException::new); List<Page> analysisPages=pages.findByAnalysisId(id); return new FunctionalModuleDeriver().derive(analysisPages, analysisPages.stream().flatMap(page->pageElements(page.getId()).stream()).toList()); }
    public List<UIElement> elements(String id){pages.findById(id).orElseThrow(java.util.NoSuchElementException::new); return pageElements(id);}
    /** Elements visible on the page itself; elements of opened forms stay out of existing listings until they get their own presentation. */
    private List<UIElement> pageElements(String pageId) { return elements.findByPageId(pageId).stream().filter(element -> element.getOpenerElementId() == null).toList(); }
    public Optional<Screenshot> screenshot(String pageId){return screenshots.findByPageId(pageId);}
    public List<AnalysisSummary> history(String applicationId) {
        if (!applications.existsById(applicationId)) throw new NoSuchElementException();
        return analyses.findByApplicationIdOrderByStartedAtDescIdDesc(applicationId).stream()
                .map(analysis -> new AnalysisSummary(analysis, pages.countByAnalysisId(analysis.getId())))
                .toList();
    }
    public record AnalysisSummary(String id, String applicationId, AnalysisStatus status, java.time.Instant startedAt, java.time.Instant completedAt, String failureMessage, long pageCount) {
        AnalysisSummary(Analysis analysis, long pageCount) { this(analysis.getId(), analysis.getApplicationId(), analysis.getStatus(), analysis.getStartedAt(), analysis.getCompletedAt(), analysis.getFailureMessage(), pageCount); }
    }
    public static class AnalysisInProgressException extends RuntimeException { public AnalysisInProgressException(){super("An analysis is already running; try again later");} }
    public static class InvalidManualInclusionApprovalException extends RuntimeException { public InvalidManualInclusionApprovalException(){super("Only unknown actions can be approved for manual inclusion");} }
}
