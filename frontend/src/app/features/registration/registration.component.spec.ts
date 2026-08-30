import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiService, ApplicationResponse } from '../../core/api.service';
import { RegistrationComponent } from './registration.component';

describe('RegistrationComponent crawler configuration', () => {
  let fixture: ComponentFixture<RegistrationComponent>;
  let component: RegistrationComponent;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(async () => {
    localStorage.clear();
    api = jasmine.createSpyObj<ApiService>('ApiService', ['createProject', 'createApplication', 'testAccess']);
    api.createProject.and.resolveTo({ id: 'project-1', name: 'Workspace' });
    api.createApplication.and.resolveTo({ id: 'app-1', projectId: 'project-1', name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', maxCrawlDepth: 4, excludedRoutes: ['/admin'] });
    await TestBed.configureTestingModule({ imports: [RegistrationComponent], providers: [{ provide: ApiService, useValue: api }, provideRouter([])] }).compileComponents();
    fixture = TestBed.createComponent(RegistrationComponent);
    component = fixture.componentInstance;
  });

  it('defaults crawler configuration and includes it in registration payload', async () => {
    component.model.update(value => ({ ...value, projectName: 'Workspace', name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', username: 'tester', password: 'secret' }));
    await component.register();
    expect(api.createApplication).toHaveBeenCalledWith('project-1', jasmine.objectContaining({ maxCrawlDepth: 2, excludedRoutes: [] }));
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
    const hydratedFixture = TestBed.createComponent(RegistrationComponent);
    expect(hydratedFixture.componentInstance.model().maxCrawlDepth).toBe(5);
    expect(hydratedFixture.componentInstance.model().excludedRoutes).toEqual(['/admin']);
  });
});
