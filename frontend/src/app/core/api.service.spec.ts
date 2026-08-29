import { TestBed } from '@angular/core/testing';
import { ApiService } from './api.service';

describe('ApiService', () => {
  let service: ApiService;
  let fetchSpy: jasmine.Spy;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [ApiService] });
    service = TestBed.inject(ApiService);
    fetchSpy = spyOn(window, 'fetch');
  });

  function response(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  }

  it('creates a project with a Promise request payload', async () => {
    fetchSpy.and.resolveTo(response({ id: 'project-1', name: 'Operations' }));
    await expectAsync(service.createProject({ name: 'Operations' })).toBeResolvedTo({ id: 'project-1', name: 'Operations' });
    expect(fetchSpy).toHaveBeenCalledWith('/api/projects', jasmine.objectContaining({ method: 'POST', body: JSON.stringify({ name: 'Operations' }) }));
  });

  it('registers an application under a project', async () => {
    const input = { name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', username: 'tester', password: 'secret' };
    fetchSpy.and.resolveTo(response({ id: 'app-1', projectId: 'project-1', ...input }));
    await service.createApplication('project-1', input);
    expect(fetchSpy).toHaveBeenCalledWith('/api/projects/project-1/applications', jasmine.objectContaining({ method: 'POST', body: JSON.stringify(input) }));
  });

  it('updates an application with a PUT request', async () => {
    const input = { name: 'Portal 2', baseUrl: 'http://localhost:4000', loginUrl: 'http://localhost:4000/login', username: 'tester', password: 'secret' };
    fetchSpy.and.resolveTo(response({ id: 'app-1', projectId: 'project-1', ...input }));
    await service.updateApplication('app-1', input);
    expect(fetchSpy).toHaveBeenCalledWith('/api/applications/app-1', jasmine.objectContaining({ method: 'PUT', body: JSON.stringify(input) }));
  });

  it('tests access without exposing credentials in the request', async () => {
    fetchSpy.and.resolveTo(response({ reachable: true, authenticated: true, message: 'Access verified' }));
    await service.testAccess('app-1');
    const [, options] = fetchSpy.calls.mostRecent().args;
    expect(options.body).toBe('{}');
    expect(options.body).not.toContain('password');
  });

  it('loads analysis evidence endpoints and requests screenshots as blobs', async () => {
    fetchSpy.and.callFake((url: string) => Promise.resolve(url.endsWith('screenshot') ? new Response(new Blob(['png'], { type: 'image/png' })) : response([])));
    await service.startAnalysis('app-1');
    await service.getAnalysis('analysis-1');
    await service.getAnalysisPages('analysis-1');
    await service.getPageElements('page-1');
    const screenshot = await service.getPageScreenshot('page-1');
    expect(screenshot).toEqual(jasmine.any(Blob));
    expect(fetchSpy.calls.allArgs().map(([url]) => url)).toContain('/api/pages/page-1/screenshot');
  });
});
