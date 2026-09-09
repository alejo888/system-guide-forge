import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { AnalysisComponent } from './analysis.component';
import { ApiService, DocumentResponse } from '../../core/api.service';

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

  beforeEach(async () => { localStorage.setItem('sgf.language', 'en'); spyOn(window, 'confirm').and.returnValue(true);
    api = jasmine.createSpyObj<ApiService>('ApiService', ['getAnalysis', 'getAnalysisPages', 'getAnalysisModules', 'getPageElements', 'getPageScreenshot', 'generateDocument', 'updateDocument', 'updateManualInclusion']);
        api.getAnalysis.and.resolveTo({ id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '', completedAt: null, failureMessage: null });
        api.getAnalysisPages.and.resolveTo([]);
        api.getAnalysisModules.and.resolveTo([]);
    api.updateDocument.and.resolveTo(document);
    await TestBed.configureTestingModule({ imports: [AnalysisComponent], providers: [
      { provide: ApiService, useValue: api }, { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'analysis-1' } } } },
    ] }).compileComponents();
    fixture = TestBed.createComponent(AnalysisComponent); component = fixture.componentInstance;
    component.analysis.set({ id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '', completedAt: null, failureMessage: null }); component.document.set(document); component.documentState.set('ready'); component.state.set('ready'); component.editTitle(document.title); component.editableSections.set(document.sections); fixture.detectChanges();
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

  it('sends the selected language and manual type and reflects persisted values', async () => {
    api.generateDocument.and.resolveTo({ ...document, language: 'en', type: 'user_manual' });
    component.analysis.set({ id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '', completedAt: null, failureMessage: null });
    component.setDocumentLanguage('es');
    await component.generateDocument();
    expect(api.generateDocument).toHaveBeenCalledWith('analysis-1', { language: 'es', type: 'user_manual' });
    expect(component.documentLanguage()).toBe('en');
    expect(component.documentType()).toBe('user_manual');
  });
  it('cancels replacement without calling the API or changing the persisted draft', async () => { component.document.set(document); component.editableTitle.set(document.title); component.setDocumentLanguage('es'); (window.confirm as jasmine.Spy).and.returnValue(false); await component.generateDocument(); expect(api.generateDocument).not.toHaveBeenCalled(); expect(component.document()).toBe(document); expect(component.documentLanguage()).toBe('es'); });
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
      it('builds stable anchor ids for navigation', () => {
    expect(component.sectionAnchor('section-1')).toBe('manual-section-section-1');
    expect(component.pageAnchor('page-1')).toBe('discovered-page-page-1');
    expect(component.moduleAnchor('Admin Users')).toBe('module-admin-users');
  });
  it('renders no-match states for page and section searches', () => {
    component.pages.set([]); component.modules.set([]); component.searchQuery.set('nothing'); component.editableSections.set(document.sections); component.state.set('ready'); component.analysis.set({ id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '', completedAt: null, failureMessage: null });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No matching results');
  });
});
