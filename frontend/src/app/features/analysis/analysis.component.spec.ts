import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { AnalysisComponent } from './analysis.component';
import { ApiService, DocumentResponse } from '../../core/api.service';

const document: DocumentResponse = {
  id: 'doc-1', title: 'Guide', applicationId: 'app-1', sourceAnalysisId: 'analysis-1', status: 'DRAFT',
  sections: [
    { id: 'section-1', position: 0, sourcePageId: 'page-1', screenshotId: 'shot-1', title: 'First', content: 'Content 1' },
    { id: 'section-2', position: 1, sourcePageId: 'page-2', screenshotId: null, title: 'Second', content: 'Content 2' },
  ],
};

describe('AnalysisComponent document editing', () => {
  let fixture: ComponentFixture<AnalysisComponent>;
  let component: AnalysisComponent;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(async () => {
    api = jasmine.createSpyObj<ApiService>('ApiService', ['getAnalysis', 'getAnalysisPages', 'getPageElements', 'getPageScreenshot', 'generateDocument', 'updateDocument']);
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
      { id: 'section-1', title: 'Renamed', content: 'Updated' }, { id: 'section-2', title: 'Second', content: 'Content 2' },
    ] });
    expect(component.editableSections()[0].sourcePageId).toBe('page-1');
    expect(component.editableSections()[0].screenshotId).toBe('shot-1');
  });

  it('reorders sections by array order', () => { component.moveSection('section-2', -1); expect(component.editableSections().map(section => section.id)).toEqual(['section-2', 'section-1']); });
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
});
