import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { ApiService, ApplicationResponse } from '../../core/api.service';
import { RegistrationComponent } from './registration.component';

describe('RegistrationComponent crawler configuration', () => {
  let fixture: ComponentFixture<RegistrationComponent>;
  let component: RegistrationComponent;
  let api: jasmine.SpyObj<ApiService>;
  let routeUrl: { path: string }[];

  beforeEach(async () => {
    localStorage.clear();
    routeUrl = [];
    api = jasmine.createSpyObj<ApiService>('ApiService', ['createProject', 'createApplication', 'testAccess']);
    api.createProject.and.resolveTo({ id: 'project-1', name: 'Workspace' });
    api.createApplication.and.resolveTo({ id: 'app-1', projectId: 'project-1', name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', maxCrawlDepth: 4, excludedRoutes: ['/admin'] });
    await TestBed.configureTestingModule({ imports: [RegistrationComponent], providers: [{ provide: ApiService, useValue: api }, provideRouter([]), { provide: ActivatedRoute, useFactory: () => ({ snapshot: { url: routeUrl } }) }] }).compileComponents();
    fixture = TestBed.createComponent(RegistrationComponent);
    component = fixture.componentInstance;
  });

  it('toggles password visibility with an accessible button without changing the value', () => {
    component.model.update(value => ({ ...value, password: 'secret' }));
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('.password-field input') as HTMLInputElement;
    const toggle = fixture.nativeElement.querySelector('.password-field button') as HTMLButtonElement;
    expect(input.type).toBe('password');
    expect(toggle.type).toBe('button');
    expect(toggle.getAttribute('aria-label')).toBe('Show password');
    expect(toggle.getAttribute('aria-pressed')).toBe('false');
    toggle.click();
    fixture.detectChanges();
    expect(input.type).toBe('text');
    expect(toggle.getAttribute('aria-label')).toBe('Hide password');
    expect(toggle.getAttribute('aria-pressed')).toBe('true');
    toggle.click();
    fixture.detectChanges();
    expect(input.type).toBe('password');
    expect(component.model().password).toBe('secret');
  });

  it('announces saving and access testing separately while registration is pending', async () => {
    let finishSaving!: (value: ApplicationResponse) => void;
    let finishTesting!: (value: Awaited<ReturnType<ApiService['testAccess']>>) => void;
    api.createApplication.and.returnValue(new Promise(resolve => { finishSaving = resolve; }));
    api.testAccess.and.returnValue(new Promise(resolve => { finishTesting = resolve; }));
    component.model.update(value => ({ ...value, projectName: 'Workspace', name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', username: 'tester', password: 'secret' }));
    fixture.detectChanges();
    const pending = component.register();
    await Promise.resolve();
    fixture.detectChanges();
    const status = fixture.nativeElement.querySelector('[role="status"]') as HTMLElement;
    expect(status.getAttribute('aria-live')).toBe('polite');
    expect(status.textContent).toContain('Saving');
    finishSaving({ id: 'app-1', projectId: 'project-1', name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="status"]')?.textContent).toContain('Testing access');
    finishTesting({ reachable: true, authenticated: true, message: 'Access verified' });
    await pending;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="status"]')).toBeNull();
  });

  it('defaults crawler configuration to zero and includes it in registration payload', async () => {
    component.model.update(value => ({ ...value, projectName: 'Workspace', name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', username: 'tester', password: 'secret' }));
    await component.register();
    expect(component.model().maxCrawlDepth).toBe(0);
    expect(api.createApplication).toHaveBeenCalledWith('project-1', jasmine.objectContaining({ maxCrawlDepth: 0, excludedRoutes: [] }));
  });

  it('renders crawler and local URL validation alerts only when invalid', () => {
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#local-url-validation')).toBeNull();
    expect(fixture.nativeElement.querySelector('#crawler-validation')).toBeNull();

    component.model.update(value => ({ ...value, baseUrl: 'https://example.com' }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#local-url-validation')?.getAttribute('role')).toBe('alert');

    component.model.update(value => ({ ...value, baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', maxCrawlDepth: 6 }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#local-url-validation')).toBeNull();
    expect(fixture.nativeElement.querySelector('#crawler-validation')?.getAttribute('role')).toBe('alert');
  });

  it('accepts IPv6 loopback URLs and rejects remote hosts', () => {
    expect(component.isLocalUrl('http://[::1]:3000')).toBeTrue();
    expect(component.isLocalUrl('https://[::1]/login')).toBeTrue();
    expect(component.isLocalUrl('https://example.com')).toBeFalse();
  });

  it('describes IPv6 loopback in English and Spanish validation messages', () => {
    expect(component.t('local-url-error')).toBe('Use an HTTP(S) URL on localhost, 127.0.0.1, or [::1].');
    expect(component.t('local-url-validation')).toBe('Use an HTTP(S) URL on localhost, 127.0.0.1, or [::1].');

    component.localization.setLanguage('es');

    expect(component.t('local-url-error')).toBe('Usá una URL HTTP(S) en localhost, 127.0.0.1 o [::1].');
    expect(component.t('local-url-validation')).toBe('Usá una URL HTTP(S) en localhost, 127.0.0.1 o [::1].');
  });

  it('accepts root and trailing-slash routes and rejects malformed routes', () => {
    component.model.update(value => ({ ...value, maxCrawlDepth: 6, excludedRoutes: ['/admin', 'admin', '/'] }));
    expect(component.crawlerConfigurationValid()).toBeFalse();
    component.model.update(value => ({ ...value, maxCrawlDepth: 0, excludedRoutes: ['/', '/admin/', '/account/settings'] }));
    expect(component.crawlerConfigurationValid()).toBeTrue();
    component.model.update(value => ({ ...value, excludedRoutes: ['//admin', '/admin//', '/admin?x=1'] }));
    expect(component.crawlerConfigurationValid()).toBeFalse();
    component.model.update(value => ({ ...value, excludedRoutes: ['/a%2Fb'] }));
    expect(component.crawlerConfigurationValid()).toBeFalse();
  });

  it('normalizes excluded routes in the registration payload', async () => {
    component.model.update(value => ({ ...value, projectName: 'Workspace', name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', username: 'tester', password: 'secret', excludedRoutes: ['/', '/admin/'] }));
    await component.register();
    expect(api.createApplication).toHaveBeenCalledWith('project-1', jasmine.objectContaining({ excludedRoutes: ['/', '/admin'] }));
  });

  it('hydrates crawler configuration from an existing application response', () => {
    const application: ApplicationResponse = { id: 'app-1', projectId: 'project-1', name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', maxCrawlDepth: 5, excludedRoutes: ['/admin'] };
    localStorage.setItem('sgf.application', JSON.stringify(application));
    routeUrl.push({ path: 'edit' });
    const hydratedFixture = TestBed.createComponent(RegistrationComponent);
    expect(hydratedFixture.componentInstance.editMode).toBeTrue();
    expect(hydratedFixture.componentInstance.model().maxCrawlDepth).toBe(5);
    expect(hydratedFixture.componentInstance.model().excludedRoutes).toEqual(['/admin']);
  });
});
