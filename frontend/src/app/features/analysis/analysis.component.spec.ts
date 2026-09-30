import { ComponentFixture, fakeAsync, TestBed, tick } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { AnalysisComponent } from './analysis.component';
import { ApiError, ApiService, DocumentResponse, LocalizationService } from '../../core/api.service';

const document: DocumentResponse = {
  id: 'doc-1', title: 'Guide', applicationId: 'app-1', sourceAnalysisId: 'analysis-1', status: 'DRAFT', language: 'en', type: 'user_manual',
  sections: [
    { id: 'section-1', position: 0, sourcePageId: 'page-1', screenshotId: 'shot-1', title: 'First', content: 'Content 1', hidden: false },
    { id: 'section-2', position: 1, sourcePageId: 'page-2', screenshotId: null, title: 'Second', content: 'Content 2', hidden: true },
  ],
};

describe('AnalysisComponent document editing', () => {
  let fixture: ComponentFixture<AnalysisComponent>;
  let component: AnalysisComponent;
  let api: jasmine.SpyObj<ApiService>;
  let router: jasmine.SpyObj<Router>;

  beforeEach(async () => { localStorage.setItem('sgf.language', 'en'); spyOn(window, 'confirm').and.returnValue(true);
    api = jasmine.createSpyObj<ApiService>('ApiService', ['getAnalysis', 'getAnalysisPages', 'getAnalysisModules', 'getPageElements', 'getPageScreenshot', 'generateDocument', 'getAnalysisDocument', 'updateDocument', 'updateManualInclusion', 'startAnalysis', 'exportDocument']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.resolveTo(true);
        api.getAnalysis.and.resolveTo({ id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '', completedAt: null, failureMessage: null });
        api.getAnalysisPages.and.resolveTo([]);
        api.getAnalysisModules.and.resolveTo([]);
        api.getAnalysisDocument.and.rejectWith(new ApiError(404, 'Document not found'));
    api.updateDocument.and.resolveTo(document);
    await TestBed.configureTestingModule({ imports: [AnalysisComponent], providers: [
      { provide: ApiService, useValue: api }, { provide: Router, useValue: router }, { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'analysis-1' } } } },
    ] }).compileComponents();
    fixture = TestBed.createComponent(AnalysisComponent); component = fixture.componentInstance;
    component.analysis.set({ id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '', completedAt: null, failureMessage: null }); component.document.set(document); component.documentState.set('ready'); component.state.set('ready'); component.editTitle(document.title); component.editableSections.set(document.sections); fixture.detectChanges();
  });

  it('retries a failed analysis with the current application configuration and opens the new analysis', async () => {
    component.analysis.set({ id: 'analysis-failed', applicationId: 'app-1', status: 'FAILED', startedAt: '', completedAt: '', failureMessage: 'Crawler failed' });
    api.startAnalysis.and.resolveTo({ id: 'analysis-new', applicationId: 'app-1', status: 'RUNNING', startedAt: '', completedAt: null, failureMessage: null });

    await component.retryAnalysis();

    expect(api.startAnalysis).toHaveBeenCalledOnceWith('app-1');
    expect(router.navigate).toHaveBeenCalledOnceWith(['/analysis', 'analysis-new']);
    expect(component.analysis()?.id).toBe('analysis-failed');
  });

  it('cancels a failed-analysis retry without changing the failed analysis', async () => {
    component.analysis.set({ id: 'analysis-failed', applicationId: 'app-1', status: 'FAILED', startedAt: '', completedAt: '', failureMessage: 'Crawler failed' });
    (window.confirm as jasmine.Spy).and.returnValue(false);

    await component.retryAnalysis();

    expect(api.startAnalysis).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
    expect(component.analysis()?.id).toBe('analysis-failed');
  });

  it('surfaces a retry error while retaining the failed analysis and its evidence', async () => {
    component.analysis.set({ id: 'analysis-failed', applicationId: 'app-1', status: 'FAILED', startedAt: '', completedAt: '', failureMessage: 'Crawler failed' });
    component.pages.set([{ id: 'page-1', analysisId: 'analysis-failed', url: 'https://example.test', title: 'Home', elements: [], screenshotUrl: null }]);
    api.startAnalysis.and.rejectWith(new Error('offline'));

    await component.retryAnalysis();

    expect(component.retryState()).toBe('error');
    expect(component.retryErrorMessage()).toBe('Could not start a new analysis. The failed analysis and its evidence remain available.');
    expect(component.analysis()?.id).toBe('analysis-failed');
    expect(component.pages()).toHaveSize(1);
  });

  it('shows an incomplete-evidence warning and retry action for failed analyses', () => {
    component.analysis.set({ id: 'analysis-failed', applicationId: 'app-1', status: 'FAILED', startedAt: '', completedAt: '', failureMessage: 'Crawler failed' });
    component.state.set('ready');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.failed-analysis-warning').textContent).toContain('Any evidence below may be incomplete');
    expect(fixture.nativeElement.querySelector('.retry-analysis-button').textContent).toContain('Retry analysis');
    expect(fixture.nativeElement.querySelector('.generation-card')).toBeNull();
  });

  it('localizes the failed-analysis warning and retry action in Spanish', () => {
    component.localization.setLanguage('es');
    component.analysis.set({ id: 'analysis-failed', applicationId: 'app-1', status: 'FAILED', startedAt: '', completedAt: '', failureMessage: 'Crawler failed' });
    component.state.set('ready');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.failed-analysis-warning').textContent).toContain('puede estar incompleta');
    expect(fixture.nativeElement.querySelector('.retry-analysis-button').textContent).toContain('Reintentar análisis');
  });

  it('discloses only fixed backend failure categories, never appended sensitive detail', () => {
    const categories = ['Browser operation timeout', 'Authentication failed', 'Unsafe navigation rejected', 'Sanitized screenshot unavailable', 'Browser navigation failed', 'Browser unavailable', 'Browser operation failed'];
    for (const category of categories) {
      component.analysis.set({ id: 'analysis-failed', applicationId: 'app-1', status: 'FAILED', startedAt: '', completedAt: '', failureMessage: `Screen analysis unavailable or failed: ${category} at http://localhost:8080/login?token=secret selector=#password` });
      fixture.detectChanges();
      const progress = fixture.nativeElement.querySelector('.progress-card');
      expect(progress.textContent).toContain('Check the local system and access settings');
      expect(progress.textContent).not.toContain('secret');
      expect(progress.textContent).not.toContain('localhost');
      expect(progress.textContent).not.toContain('#password');
      const details = fixture.nativeElement.querySelector('.failure-details');
      expect(details).not.toBeNull();
      expect(details.open).toBeFalse();
      expect(details.textContent).toContain(category);
      expect(details.textContent).not.toContain('secret');
      expect(details.textContent).not.toContain('localhost');
      expect(details.textContent).not.toContain('#password');
      expect(component.safeFailureDetail(`Screen analysis unavailable or failed: ${category} private data`)).toBe(category);
    }
    expect(component.safeFailureDetail('Screen analysis unavailable or failed: Unknown failure Browser operation timeout')).toBeNull();
    expect(component.safeFailureDetail('Navigation timed out at https://example.test?token=secret')).toBeNull();
    expect(component.safeFailureDetail('Analysis failed selector=#password')).toBeNull();
    expect(component.safeFailureDetail('Unknown failure')).toBeNull();
  });

  it('announces retry progress, blocks duplicate attempts and permits retry after a failed start', async () => {
    component.analysis.set({ id: 'analysis-failed', applicationId: 'app-1', status: 'FAILED', startedAt: '', completedAt: '', failureMessage: null });
    let rejectStart!: (reason: Error) => void;
    api.startAnalysis.and.returnValue(new Promise((_resolve, reject) => { rejectStart = reject; }));
    const pending = component.retryAnalysis();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.retry-analysis-button').disabled).toBeTrue();
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain('Starting a new analysis');
    await component.retryAnalysis();
    expect(api.startAnalysis).toHaveBeenCalledTimes(1);
    rejectStart(new Error('offline'));
    await pending;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.retry-error[role="alert"]').textContent).toContain('Could not start');
    expect(fixture.nativeElement.querySelector('.retry-analysis-button').disabled).toBeFalse();
  });

  it('localizes failure guidance and retry progress in Spanish', () => {
    component.localization.setLanguage('es');
    component.analysis.set({ id: 'analysis-failed', applicationId: 'app-1', status: 'FAILED', startedAt: '', completedAt: '', failureMessage: 'Analysis failed' });
    component.retryState.set('starting');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.progress-card').textContent).toContain('Verificá el sistema local');
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain('Iniciando un nuevo análisis');
  });

  it('edits fields and sends the ordered editable payload without traceability fields', async () => {
    component.editTitle('Edited guide'); component.editSectionTitle('section-1', 'Renamed'); component.editSectionContent('section-1', 'Updated');
    await component.saveDocument();
    expect(api.updateDocument).toHaveBeenCalledWith('doc-1', { title: 'Edited guide', sections: [
      { id: 'section-1', title: 'Renamed', content: 'Updated', hidden: false }, { id: 'section-2', title: 'Second', content: 'Content 2', hidden: true },
    ] });
    expect(component.editableSections()[0].sourcePageId).toBe('page-1');
    expect(component.editableSections()[0].screenshotId).toBe('shot-1');
  });

  it('downloads the latest persisted draft as a DOCX Blob without saving client-unsaved edits', fakeAsync(() => {
    api.exportDocument.and.resolveTo(new Blob(['docx'], { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' }));
    const createObjectUrl = spyOn(URL, 'createObjectURL').and.returnValue('blob:manual');
    const revokeObjectUrl = spyOn(URL, 'revokeObjectURL');
    const click = spyOn(HTMLAnchorElement.prototype, 'click');
    component.editTitle('Client-only unsaved title');

    component.downloadDocx();
    tick();

    expect(api.exportDocument).toHaveBeenCalledOnceWith('doc-1');
    expect(api.updateDocument).not.toHaveBeenCalled();
    expect(createObjectUrl).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:manual');
  }));

  it('defers revoking the object URL so the triggered download is not cancelled', async () => {
    api.exportDocument.and.resolveTo(new Blob(['docx']));
    spyOn(URL, 'createObjectURL').and.returnValue('blob:manual');
    const revokeObjectUrl = spyOn(URL, 'revokeObjectURL');
    spyOn(HTMLAnchorElement.prototype, 'click');
    jasmine.clock().install();
    try {
      await component.downloadDocx();
      expect(revokeObjectUrl).not.toHaveBeenCalled();
      jasmine.clock().tick(1);
      expect(revokeObjectUrl).toHaveBeenCalledWith('blob:manual');
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('falls back to manual.docx when the persisted title is null, undefined, or blank', fakeAsync(() => {
    api.exportDocument.and.resolveTo(new Blob(['docx']));
    spyOn(URL, 'createObjectURL').and.returnValue('blob:manual');
    spyOn(URL, 'revokeObjectURL');
    const click = spyOn(HTMLAnchorElement.prototype, 'click');
    component.document.set({ ...document, title: null as unknown as string });

    component.downloadDocx();
    tick();

    const link = click.calls.mostRecent().object as HTMLAnchorElement;
    expect(link.download).toBe('manual.docx');
  }));

  it('shows a translated error when the DOCX export fails, without an unhandled rejection', async () => {
    api.exportDocument.and.rejectWith(new Error('offline'));

    await component.downloadDocx();
    fixture.detectChanges();

    expect(component.docxExportState()).toBe('error');
    expect(fixture.nativeElement.querySelector('.docx-error').textContent).toContain('The DOCX could not be downloaded. Please try again.');
  });

  it('guards against a second download click while an export is already in flight', async () => {
    let resolveExport!: (blob: Blob) => void;
    api.exportDocument.and.returnValue(new Promise(resolve => { resolveExport = resolve; }));
    const createObjectUrl = spyOn(URL, 'createObjectURL').and.returnValue('blob:manual');
    spyOn(URL, 'revokeObjectURL');
    spyOn(HTMLAnchorElement.prototype, 'click');

    const first = component.downloadDocx();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.download-docx-button').disabled).toBeTrue();
    const second = component.downloadDocx();
    resolveExport(new Blob(['docx']));
    await Promise.all([first, second]);

    expect(api.exportDocument).toHaveBeenCalledTimes(1);
    expect(createObjectUrl).toHaveBeenCalledTimes(1);
  });

  it('sends the selected language and manual type and reflects persisted values', async () => {
    api.generateDocument.and.resolveTo({ ...document, language: 'en', type: 'user_manual' });
    component.analysis.set({ id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '', completedAt: null, failureMessage: null });
    component.setDocumentLanguage('es');
    await component.generateDocument();
    expect(api.generateDocument).toHaveBeenCalledWith('analysis-1', { language: 'es', type: 'user_manual', confirmReplacement: true });
    expect(component.documentLanguage()).toBe('en');
    expect(component.documentType()).toBe('user_manual');
  });
  it('cancels replacement without calling the API or changing the persisted draft', async () => { component.document.set(document); component.editableTitle.set(document.title); component.setDocumentLanguage('es'); (window.confirm as jasmine.Spy).and.returnValue(false); await component.generateDocument(); expect(api.generateDocument).not.toHaveBeenCalled(); expect(component.document()).toBe(document); expect(component.documentLanguage()).toBe('es'); });
  it('confirms once and explicitly retries when the backend requires replacement confirmation', async () => {
    component.setDocumentLanguage('en');
    api.generateDocument.and.callFake((_analysisId, payload) => payload.confirmReplacement
      ? Promise.resolve({ ...document, language: 'es' as const })
      : Promise.reject(new ApiError(409, 'Replacing an existing draft with a different language or type requires confirmation', 'DRAFT_REPLACEMENT_CONFIRMATION_REQUIRED')));

    await component.generateDocument();

    expect(window.confirm).toHaveBeenCalledTimes(1);
    expect(api.generateDocument.calls.allArgs()).toEqual([
      ['analysis-1', { language: 'en', type: 'user_manual', confirmReplacement: false }],
      ['analysis-1', { language: 'en', type: 'user_manual', confirmReplacement: true }],
    ]);
    expect(component.documentLanguage()).toBe('es');
  });
  it('preserves the loaded draft when backend-required replacement confirmation is cancelled', async () => {
    api.generateDocument.and.rejectWith(new ApiError(409, 'Replacing an existing draft with a different language or type requires confirmation', 'DRAFT_REPLACEMENT_CONFIRMATION_REQUIRED'));
    (window.confirm as jasmine.Spy).and.returnValue(false);

    await component.generateDocument();

    expect(window.confirm).toHaveBeenCalledTimes(1);
    expect(api.generateDocument).toHaveBeenCalledOnceWith('analysis-1', { language: 'en', type: 'user_manual', confirmReplacement: false });
    expect(component.document()).toBe(document);
    expect(component.documentState()).toBe('ready');
  });
      it('reorders sections by array order', () => { component.moveSection('section-2', -1); expect(component.editableSections().map(section => section.id)).toEqual(['section-2', 'section-1']); });
    it('keeps hidden sections in the editable array while excluding them from the reader-facing draft', () => { expect(component.visibleSections().map(section => section.id)).toEqual(['section-1']); expect(component.hiddenSections().map(section => section.id)).toEqual(['section-2']); component.setSectionHidden('section-2', false); expect(component.editableSections()[1].hidden).toBeFalse(); });
    it('renders a traceable hidden management row outside the draft content and contents list', () => { fixture.detectChanges(); expect(fixture.nativeElement.querySelectorAll('.draft-card').length).toBe(1); expect(fixture.nativeElement.querySelector('.hidden-section-row').textContent).toContain('Source page: page-2'); expect(fixture.nativeElement.querySelector('.hidden-section-row').textContent).toContain('Screenshot: Unavailable'); expect(fixture.nativeElement.querySelector('.toc-card').textContent).toContain('First'); expect(fixture.nativeElement.querySelector('.toc-card').textContent).not.toContain('Second'); });
  it('keeps page evidence and document editing available when module loading fails', async () => {
    const analysis = { id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED' as const, startedAt: '', completedAt: null, failureMessage: null };
    const page = { id: 'page-1', analysisId: 'analysis-1', url: 'https://example.test', title: 'Home' };
    api.getAnalysis.and.resolveTo(analysis);
    api.getAnalysisPages.and.resolveTo([page]);
    api.getPageElements.and.resolveTo([]);
    api.getPageScreenshot.and.resolveTo(new Blob());
    api.getAnalysisModules.and.rejectWith(new Error('modules unavailable'));
    api.generateDocument.and.resolveTo(document);

    await (component as unknown as { load(id: string): Promise<void> }).load('analysis-1');

    expect(component.state()).toBe('ready');
    expect(component.pages()).toEqual([{ ...page, elements: [], screenshotUrl: jasmine.any(String) }]);
    expect(component.modules()).toEqual([{ key: 'unassigned', name: 'Unassigned pages (module data unavailable)', pages: [page] }]);
    await component.generateDocument();
    expect(component.documentState()).toBe('ready');
  });
  it('renders and preserves source page and screenshot traceability', async () => {
    const section = fixture.nativeElement.querySelector('.draft-card');
    expect(section.textContent).toContain('Source page: page-1');
    expect(section.textContent).toContain('Screenshot: shot-1');
    component.editSectionTitle('section-1', 'Edited first');
    await component.saveDocument();
    expect(component.editableSections()[0]).toEqual(jasmine.objectContaining({ sourcePageId: 'page-1', screenshotId: 'shot-1' }));
  });
  it('exposes save success and error states', async () => {
    await component.saveDocument(); expect(component.saveState()).toBe('success');
    api.updateDocument.and.rejectWith(new Error('failed')); await component.saveDocument(); expect(component.saveState()).toBe('error');
  });
  it('filters pages and sections across searchable evidence fields', () => {
    const page = { id: 'page-1', analysisId: 'analysis-1', url: 'https://example.test/settings', title: 'Settings' };
    component.pages.set([{ ...page, elements: [{ id: 'element-1', kind: 'button', selector: '#save-settings', accessibleName: 'Save', actionClassification: 'MUTATING', manualInclusionApproved: false }], screenshotUrl: null }]);
    component.modules.set([{ key: 'admin', name: 'Administration', pages: [page] }]);
    component.searchQuery.set('save-settings');
    expect(component.filteredModules()[0].pages).toEqual([page]);
    component.searchQuery.set('missing');
    expect(component.filteredModules()).toEqual([]);
    expect(component.filteredSections()).toEqual([]);
  });
  it('places manual generation before the collapsed UNKNOWN review and page evidence', () => {
    const page = { id: 'page-1', analysisId: 'analysis-1', url: 'https://example.test', title: 'Home' };
    component.pages.set([{ ...page, elements: [{ id: 'element-1', kind: 'button', selector: '#advanced', accessibleName: 'Advanced', actionClassification: 'UNKNOWN' as const, manualInclusionApproved: false }], screenshotUrl: 'blob:screen' }]);
    component.modules.set([{ key: 'home', name: 'Home', pages: [page] }]);
    component.analysis.set({ id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '', completedAt: null, failureMessage: null });
    component.state.set('ready');
    fixture.detectChanges();

    const review = fixture.nativeElement.querySelector('.manual-review-card');
    const manualCard = fixture.nativeElement.querySelector('.generation-card');
    const draft = fixture.nativeElement.querySelector('.draft-section');
    const screenshot = fixture.nativeElement.querySelector('.screenshot');
    expect(review).not.toBeNull();
        expect(review.open).toBeFalse();
        expect(review.textContent).toContain('1 uncertain items');
    expect(manualCard).not.toBeNull();
    expect(draft).not.toBeNull();
    expect(screenshot).not.toBeNull();
    expect(manualCard.compareDocumentPosition(review) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(review.compareDocumentPosition(draft) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(manualCard.compareDocumentPosition(screenshot) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
  it('approves an unknown item for documentation without executing it and clears a stale draft', async () => {
    const unknown = { id: 'element-1', kind: 'button', selector: '#advanced', accessibleName: 'Advanced', actionClassification: 'UNKNOWN' as const, manualInclusionApproved: false };
    component.pages.set([{ id: 'page-1', analysisId: 'analysis-1', url: 'https://example.test', title: 'Home', elements: [unknown], screenshotUrl: null }]);
    api.updateManualInclusion.and.resolveTo({ ...unknown, manualInclusionApproved: true });

    await component.setManualInclusionApproval(unknown.id, true);

    expect(api.updateManualInclusion).toHaveBeenCalledWith(unknown.id, true);
    expect(component.pages()[0].elements[0].manualInclusionApproved).toBeTrue();
    expect(component.document()).toBeNull();
    expect(component.documentState()).toBe('idle');
  });
  it('uses route labels when discovered pages repeat the same title', () => {
        const first = { id: 'page-1', analysisId: 'analysis-1', url: 'https://example.test/admin/users', title: 'FlowPilot' };
        const second = { id: 'page-2', analysisId: 'analysis-1', url: 'https://example.test/reports', title: 'FlowPilot' };
        component.pages.set([{ ...first, elements: [], screenshotUrl: null }, { ...second, elements: [], screenshotUrl: null }]);
        expect(component.pageLabel(first)).toBe('FlowPilot · /admin/users');
        expect(component.pageLabel(second)).toBe('FlowPilot · /reports');
      });
      it('prefers the visible heading over the static title', () => {
        const page = { id: 'page-1', analysisId: 'analysis-1', url: 'https://example.test/projects', title: 'FlowPilot', heading: 'Projects' };
        component.pages.set([{ ...page, elements: [], screenshotUrl: null }]);
        expect(component.pageLabel(page)).toBe('Projects');
      });
      it('falls back to the title when the heading is blank or missing', () => {
        const blank = { id: 'page-1', analysisId: 'analysis-1', url: 'https://example.test/a', title: 'Alpha', heading: '   ' };
        const missing = { id: 'page-2', analysisId: 'analysis-1', url: 'https://example.test/b', title: 'Beta', heading: null };
        component.pages.set([{ ...blank, elements: [], screenshotUrl: null }, { ...missing, elements: [], screenshotUrl: null }]);
        expect(component.pageLabel(blank)).toBe('Alpha');
        expect(component.pageLabel(missing)).toBe('Beta');
      });
      it('keeps route disambiguation when headings repeat', () => {
        const first = { id: 'page-1', analysisId: 'analysis-1', url: 'https://example.test/projects/1/board', title: 'FlowPilot', heading: 'Board' };
        const second = { id: 'page-2', analysisId: 'analysis-1', url: 'https://example.test/projects/2/board', title: 'FlowPilot', heading: 'Board' };
        component.pages.set([{ ...first, elements: [], screenshotUrl: null }, { ...second, elements: [], screenshotUrl: null }]);
        expect(component.pageLabel(first)).toBe('Board · /projects/1/board');
        expect(component.pageLabel(second)).toBe('Board · /projects/2/board');
      });
      it('finds a page by its heading', () => {
        const page = { id: 'page-1', analysisId: 'analysis-1', url: 'https://example.test/x', title: 'FlowPilot', heading: 'Quarterly Roadmap' };
        component.pages.set([{ ...page, elements: [], screenshotUrl: null }]);
        component.modules.set([{ key: 'admin', name: 'Administration', pages: [page] }]);
        component.searchQuery.set('roadmap');
        expect(component.filteredModules()[0].pages).toEqual([page]);
      });
      it('builds stable anchor ids for navigation', () => {
    expect(component.sectionAnchor('section-1')).toBe('manual-section-section-1');
    expect(component.pageAnchor('page-1')).toBe('discovered-page-page-1');
    expect(component.moduleAnchor('Admin Users')).toBe('module-admin-users');
  });
  it('labels the login page as the sign-in screen and keeps it first in the evidence view', () => {
    const loginPage = { id: 'page-login', analysisId: 'analysis-1', url: 'https://example.test/login', title: 'Login', kind: 'LOGIN' as const };
    const homePage = { id: 'page-home', analysisId: 'analysis-1', url: 'https://example.test/home', title: 'Home', kind: null };
    component.pages.set([{ ...loginPage, elements: [], screenshotUrl: null }, { ...homePage, elements: [], screenshotUrl: null }]);
    component.modules.set([{ key: 'login', name: 'Login', pages: [loginPage] }, { key: 'home', name: 'Home', pages: [homePage] }]);
    component.analysis.set({ id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '', completedAt: null, failureMessage: null });
    component.state.set('ready');
    fixture.detectChanges();

    expect(component.isLoginPage(loginPage)).toBeTrue();
    expect(component.isLoginPage(homePage)).toBeFalse();
    const badges = fixture.nativeElement.querySelectorAll('.login-badge');
    expect(badges.length).toBe(1);
    expect(badges[0].textContent).toContain('Sign-in screen');
    const pageCards = fixture.nativeElement.querySelectorAll('.page-card');
    expect(pageCards[0].querySelector('.login-badge')).not.toBeNull();
  });

  it('localizes the login page badge in Spanish', () => {
    component.localization.setLanguage('es');
    const loginPage = { id: 'page-login', analysisId: 'analysis-1', url: 'https://example.test/login', title: 'Login', kind: 'LOGIN' as const };
    component.pages.set([{ ...loginPage, elements: [], screenshotUrl: null }]);
    component.modules.set([{ key: 'login', name: 'Login', pages: [loginPage] }]);
    component.analysis.set({ id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '', completedAt: null, failureMessage: null });
    component.state.set('ready');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.login-badge').textContent).toContain('Pantalla de inicio de sesión');
  });

  it('renders no-match states for page and section searches', () => {
    component.pages.set([]); component.modules.set([]); component.searchQuery.set('nothing'); component.editableSections.set(document.sections); component.state.set('ready'); component.analysis.set({ id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '', completedAt: null, failureMessage: null });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No matching results');
  });
});

describe('AnalysisComponent auto-loaded draft', () => {
  let fixture: ComponentFixture<AnalysisComponent>;
  let component: AnalysisComponent;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(async () => {
    localStorage.setItem('sgf.language', 'en');
    api = jasmine.createSpyObj<ApiService>('ApiService', ['getAnalysis', 'getAnalysisPages', 'getAnalysisModules', 'getPageElements', 'getPageScreenshot', 'generateDocument', 'getAnalysisDocument', 'updateDocument', 'updateManualInclusion', 'startAnalysis', 'exportDocument']);
    api.getAnalysis.and.resolveTo({ id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '', completedAt: null, failureMessage: null });
    api.getAnalysisPages.and.resolveTo([]);
    api.getAnalysisModules.and.resolveTo([]);
    await TestBed.configureTestingModule({ imports: [AnalysisComponent], providers: [
      { provide: ApiService, useValue: api }, { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) }, { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'analysis-1' } } } },
    ] }).compileComponents();
  });

  it('shows a non-blocking loading indicator while the existing draft is being looked up, with Generate enabled', async () => {
    let resolveAutoLoad!: (value: import('../../core/api.service').DocumentResponse) => void;
    api.getAnalysisDocument.and.returnValue(new Promise(resolve => { resolveAutoLoad = resolve; }));
    fixture = TestBed.createComponent(AnalysisComponent); component = fixture.componentInstance;
    fixture.detectChanges();
    for (let tick = 0; tick < 50 && !api.getAnalysisDocument.calls.count(); tick++) await Promise.resolve();
    fixture.detectChanges();

    expect(component.draftLookupState()).toBe('loading');
    const status = fixture.nativeElement.querySelector('.draft-lookup-status');
    expect(status).not.toBeNull();
    expect(status.getAttribute('role')).toBe('status');
    expect(status.textContent).toContain('Looking for an existing draft');
    expect(fixture.nativeElement.querySelector('.generation-card button.button').disabled).toBeFalse();

    resolveAutoLoad(document);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.draftLookupState()).toBe('idle');
    expect(fixture.nativeElement.querySelector('.draft-lookup-status')).toBeNull();
  });

  it('hides the loading indicator once the draft lookup 404s (no existing draft)', async () => {
    api.getAnalysisDocument.and.rejectWith(new ApiError(404, 'Document not found'));
    fixture = TestBed.createComponent(AnalysisComponent); component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.draftLookupState()).toBe('idle');
    expect(fixture.nativeElement.querySelector('.draft-lookup-status')).toBeNull();
  });

  it('hides the loading indicator once the draft lookup fails for another reason', async () => {
    api.getAnalysisDocument.and.rejectWith(new Error('network down'));
    fixture = TestBed.createComponent(AnalysisComponent); component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.draftLookupState()).toBe('idle');
    expect(fixture.nativeElement.querySelector('.draft-lookup-status')).toBeNull();
  });

  it('hides the loading indicator once the user starts generating a draft', async () => {
    let resolveAutoLoad!: (value: import('../../core/api.service').DocumentResponse) => void;
    api.getAnalysisDocument.and.returnValue(new Promise(resolve => { resolveAutoLoad = resolve; }));
    api.generateDocument.and.resolveTo({ ...document, id: 'doc-generated' });
    fixture = TestBed.createComponent(AnalysisComponent); component = fixture.componentInstance;
    fixture.detectChanges();
    for (let tick = 0; tick < 50 && !api.getAnalysisDocument.calls.count(); tick++) await Promise.resolve();
    expect(component.draftLookupState()).toBe('loading');

    await component.generateDocument();
    fixture.detectChanges();

    expect(component.draftLookupState()).toBe('idle');
    expect(fixture.nativeElement.querySelector('.draft-lookup-status')).toBeNull();
    resolveAutoLoad(document);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.draftLookupState()).toBe('idle');
    expect(fixture.nativeElement.querySelector('.draft-lookup-status')).toBeNull();
    expect(component.document()?.id).toBe('doc-generated');
  });

  it('shows the existing draft (including the Download DOCX button) without generating', async () => {
    api.getAnalysisDocument.and.resolveTo(document);
    fixture = TestBed.createComponent(AnalysisComponent); component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.getAnalysisDocument).toHaveBeenCalledOnceWith('analysis-1');
    expect(api.generateDocument).not.toHaveBeenCalled();
    expect(component.documentState()).toBe('ready');
    expect(component.document()).toEqual(document);
    expect(fixture.nativeElement.querySelector('.download-docx-button')).not.toBeNull();
  });

  it('stays idle without an error when the analysis has no existing draft', async () => {
    api.getAnalysisDocument.and.rejectWith(new ApiError(404, 'Document not found'));
    fixture = TestBed.createComponent(AnalysisComponent); component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.documentState()).toBe('idle');
    expect(component.document()).toBeNull();
    expect(fixture.nativeElement.querySelector('.document-error')).toBeNull();
  });

  it('keeps generation available without a blocking error when the draft lookup fails for another reason', async () => {
    api.getAnalysisDocument.and.rejectWith(new Error('network down'));
    fixture = TestBed.createComponent(AnalysisComponent); component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.documentState()).toBe('idle');
    expect(fixture.nativeElement.querySelector('.document-error')).toBeNull();
    expect(fixture.nativeElement.querySelector('.generation-card button.button').disabled).toBeFalse();
  });

  it('ignores a slow auto-loaded draft once the user has already generated one', async () => {
    let resolveAutoLoad!: (value: import('../../core/api.service').DocumentResponse) => void;
    api.getAnalysisDocument.and.returnValue(new Promise(resolve => { resolveAutoLoad = resolve; }));
    const generated = { ...document, id: 'doc-generated' };
    api.generateDocument.and.resolveTo(generated);
    fixture = TestBed.createComponent(AnalysisComponent); component = fixture.componentInstance;
    fixture.detectChanges();
    for (let tick = 0; tick < 50 && !api.getAnalysisDocument.calls.count(); tick++) await Promise.resolve();
    expect(api.getAnalysisDocument).toHaveBeenCalledTimes(1);

    await component.generateDocument();
    resolveAutoLoad(document);
    await fixture.whenStable();

    expect(component.document()?.id).toBe('doc-generated');
  });
});

describe('AnalysisComponent heading and completed status text', () => {
  let fixture: ComponentFixture<AnalysisComponent>;
  let component: AnalysisComponent;

  beforeEach(async () => {
    localStorage.setItem('sgf.language', 'en');
    const api = jasmine.createSpyObj<ApiService>('ApiService', ['getAnalysis', 'getAnalysisPages', 'getAnalysisModules', 'getAnalysisDocument']);
    api.getAnalysis.and.resolveTo({ id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '', completedAt: null, failureMessage: null });
    api.getAnalysisPages.and.resolveTo([]);
    api.getAnalysisModules.and.resolveTo([]);
    api.getAnalysisDocument.and.rejectWith(new ApiError(404, 'Document not found'));
    await TestBed.configureTestingModule({ imports: [AnalysisComponent], providers: [
      { provide: ApiService, useValue: api }, { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) }, { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'analysis-1' } } } },
    ] }).compileComponents();
    fixture = TestBed.createComponent(AnalysisComponent); component = fixture.componentInstance;
    component.analysis.set({ id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '', completedAt: null, failureMessage: null }); component.state.set('ready');
  });

  const evidence = (count: number) => Array.from({ length: count }, (_, index) => ({ id: `page-${index}`, analysisId: 'analysis-1', url: `http://localhost/p${index}`, title: `P${index}`, elements: [], screenshotUrl: null }) as unknown as Parameters<AnalysisComponent['pages']['set']>[0][number]);
  const text = (selector: string) => (fixture.nativeElement.querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim();

  it('reads the Spanish heading once and keeps the English heading', () => {
    TestBed.inject(LocalizationService).setLanguage('es');
    fixture.detectChanges();
    expect(text('h1')).toBe('Panel de análisis.');
    TestBed.inject(LocalizationService).setLanguage('en');
    fixture.detectChanges();
    expect(text('h1')).toBe('Analysis dashboard.');
  });

  it('states the real analyzed page count in Spanish and English with singular forms', () => {
    const localization = TestBed.inject(LocalizationService);
    localization.setLanguage('es');
    component.pages.set(evidence(7));
    fixture.detectChanges();
    expect(text('.progress-card p')).toBe('Se analizaron 7 páginas sin ejecutar controles.');
    component.pages.set(evidence(1));
    fixture.detectChanges();
    expect(text('.progress-card p')).toBe('Se analizó 1 página sin ejecutar controles.');
    localization.setLanguage('en');
    fixture.detectChanges();
    expect(text('.progress-card p')).toBe('Analyzed 1 page without running any controls.');
    component.pages.set(evidence(3));
    fixture.detectChanges();
    expect(text('.progress-card p')).toBe('Analyzed 3 pages without running any controls.');
  });
});
