package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AnalysisService {
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
            Set<String> visited = new HashSet<>();
            int persistedPages = 0;
            if (result.loginPage() != null) {
                persistPage(analysis, result.loginPage().url(), result.loginPage().title(), result.loginPage().elements(), result.loginPage().sanitizedScreenshot(), visited, PageKind.LOGIN);
                if (!visited.isEmpty()) persistedPages++;
            }
            persistPage(analysis, result.url(), result.title(), result.elements(), result.sanitizedScreenshot(), visited, null);
            persistedPages++;
            int maxCrawlDepth = Math.min(app.getMaxCrawlDepth(), MAX_CRAWL_DEPTH);
            for (var discovered : result.discoveredPages()) {
                if (persistedPages >= MAX_CRAWL_PAGES) break;
                if (discovered.classification() == ActionClassification.SAFE && discovered.depth() <= maxCrawlDepth) {
                    int before = visited.size();
                    persistPage(analysis, discovered.url(), discovered.title(), discovered.elements(), discovered.sanitizedScreenshot(), visited, null);
                    if (visited.size() > before) persistedPages++;
                }
            }
            analysis.complete(); return analyses.save(analysis);
        } catch(Exception ex){analysis.fail(ex.getMessage()); return analyses.save(analysis);}
    }
    private void persistPage(Analysis analysis, String url, String title, List<ScreenAnalysisAdapter.DetectedElement> detected, byte[] screenshot, Set<String> visited, PageKind kind) {
        if (!visited.add(url)) return;
        Page page=pages.save(new Page(analysis.getId(),url,title,kind));
        for(var e:detected) elements.save(new UIElement(page.getId(),e.kind(),e.selector(),e.accessibleName(),e.classification()));
        if(screenshot!=null) screenshots.save(new Screenshot(page.getId(),screenshot));
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
    public List<FunctionalModuleDeriver.Module> modules(String id){ analyses.findById(id).orElseThrow(java.util.NoSuchElementException::new); return new FunctionalModuleDeriver().derive(pages.findByAnalysisId(id)); }
    public List<UIElement> elements(String id){pages.findById(id).orElseThrow(java.util.NoSuchElementException::new); return elements.findByPageId(id);}
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
