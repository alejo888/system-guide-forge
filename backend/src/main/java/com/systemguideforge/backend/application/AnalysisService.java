package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class AnalysisService {
    public static final int MAX_ACTIVE_ANALYSES = 1;
    private final AnalysisRepository analyses; private final PageRepository pages; private final UIElementRepository elements; private final ScreenshotRepository screenshots; private final TargetApplicationRepository applications; private final CredentialProtector protector; private final ScreenAnalysisAdapter adapter;
    public AnalysisService(AnalysisRepository analyses,PageRepository pages,UIElementRepository elements,ScreenshotRepository screenshots,TargetApplicationRepository applications,CredentialProtector protector,ScreenAnalysisAdapter adapter){this.analyses=analyses;this.pages=pages;this.elements=elements;this.screenshots=screenshots;this.applications=applications;this.protector=protector;this.adapter=adapter;}
    public synchronized Analysis start(String applicationId){
        if (MAX_ACTIVE_ANALYSES == 1 && analyses.existsByStatusIn(List.of(AnalysisStatus.RUNNING))) throw new AnalysisInProgressException();
        TargetApplication app=applications.findById(applicationId).orElseThrow(); Analysis analysis=analyses.saveAndFlush(new Analysis(applicationId));
        try { ScreenAnalysisAdapter.ScreenAnalysisResult result=adapter.analyze(app,protector.decrypt(app.getUsernameEncrypted()),protector.decrypt(app.getPasswordEncrypted())); Page page=pages.save(new Page(analysis.getId(),result.url(),result.title()));
            for(var e:result.elements()) elements.save(new UIElement(page.getId(),e.kind(),e.selector(),e.accessibleName(),e.classification()));
            if(result.sanitizedScreenshot()!=null) screenshots.save(new Screenshot(page.getId(),result.sanitizedScreenshot())); analysis.complete(); return analyses.save(analysis);
        } catch(Exception ex){analysis.fail(ex.getMessage()); return analyses.save(analysis);}
    }
    public Optional<Analysis> get(String id){return analyses.findById(id);} public List<Page> pages(String id){return pages.findByAnalysisId(id);} public List<UIElement> elements(String id){return elements.findByPageId(id);} public Optional<Screenshot> screenshot(String pageId){return screenshots.findByPageId(pageId);}
    public static class AnalysisInProgressException extends RuntimeException { public AnalysisInProgressException(){super("An analysis is already running; try again later");} }
}
