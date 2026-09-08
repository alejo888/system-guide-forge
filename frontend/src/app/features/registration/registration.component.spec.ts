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
