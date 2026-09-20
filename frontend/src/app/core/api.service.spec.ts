import { TestBed } from '@angular/core/testing';
import { ApiService, AnalysisSummaryResponse, DocumentUpdatePayload, LocalizationService } from './api.service';

describe('ApiService', () => {
  let service: ApiService; let fetchSpy: jasmine.Spy;
  beforeEach(() => { TestBed.configureTestingModule({ providers: [ApiService] }); service = TestBed.inject(ApiService); fetchSpy = spyOn(window, 'fetch'); });
  function response(body: unknown, status = 200): Response { return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }); }
  it('creates a project with a Promise request payload', async () => { fetchSpy.and.resolveTo(response({ id: 'project-1', name: 'Operations' })); await expectAsync(service.createProject({ name: 'Operations' })).toBeResolvedTo({ id: 'project-1', name: 'Operations' }); expect(fetchSpy).toHaveBeenCalledWith('/api/projects', jasmine.objectContaining({ method: 'POST', body: JSON.stringify({ name: 'Operations' }) })); });
  it('registers an application under a project', async () => { const input = { name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', username: 'tester', password: 'secret' }; fetchSpy.and.resolveTo(response({ id: 'app-1', projectId: 'project-1', ...input })); await service.createApplication('project-1', input); expect(fetchSpy).toHaveBeenCalledWith('/api/projects/project-1/applications', jasmine.objectContaining({ method: 'POST', body: JSON.stringify(input) })); });
  it('updates an application with a PUT request', async () => { const input = { name: 'Portal 2', baseUrl: 'http://localhost:4000', loginUrl: 'http://localhost:4000/login', username: 'tester', password: 'secret' }; fetchSpy.and.resolveTo(response({ id: 'app-1', projectId: 'project-1', ...input })); await service.updateApplication('app-1', input); expect(fetchSpy).toHaveBeenCalledWith('/api/applications/app-1', jasmine.objectContaining({ method: 'PUT', body: JSON.stringify(input) })); });
  it('sends crawler configuration in create and update payloads', async () => { const input = { name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', username: 'tester', password: 'secret', maxCrawlDepth: 5, excludedRoutes: ['/admin', '/settings'] }; fetchSpy.and.callFake(() => Promise.resolve(response({ id: 'app-1', projectId: 'project-1', name: input.name, baseUrl: input.baseUrl, loginUrl: input.loginUrl, maxCrawlDepth: 5, excludedRoutes: input.excludedRoutes }))); await service.createApplication('project-1', input); await service.updateApplication('app-1', input); expect(fetchSpy.calls.argsFor(0)[1]).toEqual(jasmine.objectContaining({ body: JSON.stringify(input) })); expect(fetchSpy.calls.argsFor(1)[1]).toEqual(jasmine.objectContaining({ body: JSON.stringify(input) })); });
  it('updates a document with only editable fields in array order', async () => { const payload: DocumentUpdatePayload = { title: 'Updated guide', sections: [{ id: 'section-2', title: 'Second', content: 'Content 2', hidden: true }, { id: 'section-1', title: 'First', content: 'Content 1', hidden: false }] }; fetchSpy.and.resolveTo(response({})); await service.updateDocument('doc-1', payload); expect(fetchSpy).toHaveBeenCalledWith('/api/documents/doc-1', jasmine.objectContaining({ method: 'PUT', body: JSON.stringify(payload) })); });
  it('tests access without exposing credentials in the request', async () => { fetchSpy.and.resolveTo(response({ reachable: true, authenticated: true, message: 'Access verified' })); await service.testAccess('app-1'); const [, options] = fetchSpy.calls.mostRecent().args; expect(options.body).toBe('{}'); expect(options.body).not.toContain('password'); });
  it('generates and loads a document draft', async () => { fetchSpy.and.callFake(() => Promise.resolve(response({ id: 'doc-1', title: 'Guide', applicationId: 'app-1', sourceAnalysisId: 'analysis-1', status: 'DRAFT', language: 'en', type: 'user_manual', sections: [] }))); await service.generateDocument('analysis-1', { language: 'en', type: 'user_manual', confirmReplacement: false }); await service.getDocument('doc-1'); expect(fetchSpy.calls.allArgs().map(([url]) => url)).toEqual(['/api/analyses/analysis-1/document', '/api/documents/doc-1']); expect(fetchSpy.calls.argsFor(0)[1]).toEqual(jasmine.objectContaining({ method: 'POST', body: JSON.stringify({ language: 'en', type: 'user_manual', confirmReplacement: false }) })); });
  it('exposes confirmation-required API errors for an explicit retry', async () => {
    fetchSpy.and.resolveTo(response({ message: 'Replacing an existing draft with a different language or type requires confirmation', code: 'DRAFT_REPLACEMENT_CONFIRMATION_REQUIRED' }, 409));

    try {
      await service.generateDocument('analysis-1', { language: 'es', type: 'user_manual' });
      fail('Expected a confirmation-required API error');
    } catch (error) {
      expect(error).toEqual(jasmine.objectContaining({ name: 'ApiError', status: 409, code: 'DRAFT_REPLACEMENT_CONFIRMATION_REQUIRED' }));
    }
  });

  it('loads analysis evidence endpoints and requests screenshots as blobs', async () => { fetchSpy.and.callFake((url: string) => Promise.resolve(url.endsWith('screenshot') ? new Response(new Blob(['png'], { type: 'image/png' })) : response([]))); await service.startAnalysis('app-1'); await service.getAnalysis('analysis-1'); await service.getAnalysisPages('analysis-1'); await service.getPageElements('page-1'); const screenshot = await service.getPageScreenshot('page-1'); expect(screenshot).toEqual(jasmine.any(Blob)); expect(fetchSpy.calls.allArgs().map(([url]) => url)).toContain('/api/pages/page-1/screenshot'); });
  it('loads analysis history for an application', async () => { const analyses: AnalysisSummaryResponse[] = [{ id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T00:01:00Z', failureMessage: null, pageCount: 2 }]; fetchSpy.and.resolveTo(response(analyses)); await expectAsync(service.getApplicationAnalyses('app-1')).toBeResolvedTo(analyses); expect(fetchSpy).toHaveBeenCalledWith('/api/applications/app-1/analyses', jasmine.any(Object)); });

  it('translates known keys and falls back to English for missing Spanish keys', () => {
    const localization = new LocalizationService();
    localization.setLanguage('es');
    expect(localization.t('overview')).toBe('Resumen');
    expect(localization.t('analysis-subtitle')).toBe('Evidencia segura descubierta del sistema registrado.');
    expect(localization.t('missing-key')).toBe('missing key');
  });

  it('persists language changes and normalizes invalid values to English', () => {
    localStorage.setItem('sgf.language', 'fr');
    const localization = new LocalizationService();
    expect(localization.language()).toBe('en');
    localization.setLanguage('es');
    expect(localStorage.getItem('sgf.language')).toBe('es');
    localization.setLanguage('invalid' as 'en');
    expect(localization.language()).toBe('en');
    expect(localStorage.getItem('sgf.language')).toBe('en');
  });
});
