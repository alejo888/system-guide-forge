import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { ApiError, ApiService, ApplicationResponse, LocalizationService } from '../../core/api.service';
import { RegistrationComponent } from './registration.component';

describe('RegistrationComponent crawler configuration', () => {
  let fixture: ComponentFixture<RegistrationComponent>;
  let component: RegistrationComponent;
  let api: jasmine.SpyObj<ApiService>;
  let routeUrl: { path: string }[];
  let routeParams: Record<string, string>;

  beforeEach(async () => {
    localStorage.clear();
    routeUrl = [];
    routeParams = {};
    api = jasmine.createSpyObj<ApiService>('ApiService', ['createProject', 'createApplication', 'testAccess', 'getApplication', 'updateApplication']);
    api.createProject.and.resolveTo({ id: 'project-1', name: 'Workspace' });
    api.createApplication.and.resolveTo({ id: 'app-1', projectId: 'project-1', name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', maxCrawlDepth: 4, excludedRoutes: ['/admin'] });
    await TestBed.configureTestingModule({ imports: [RegistrationComponent], providers: [{ provide: ApiService, useValue: api }, provideRouter([]), { provide: ActivatedRoute, useFactory: () => ({ snapshot: { url: routeUrl, paramMap: convertToParamMap(routeParams) } }) }] }).compileComponents();
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
    expect(api.createApplication).toHaveBeenCalledWith('project-1', jasmine.objectContaining({ maxCrawlDepth: 0, excludedRoutes: [], approvedOpeners: [] }));
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

  describe('approved openers editor', () => {
    const validAccess = { projectName: 'Workspace', name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', username: 'tester', password: 'secret' };
    const rows = (): HTMLElement[] => Array.from(fixture.nativeElement.querySelectorAll('.opener-row'));
    const addButton = (): HTMLButtonElement => fixture.nativeElement.querySelector('.opener-add');
    const submitButton = (): HTMLButtonElement => fixture.nativeElement.querySelector('button[type="submit"]');
    function type(input: HTMLInputElement, value: string): void { input.value = value; input.dispatchEvent(new Event('input')); }
    function fillRow(index: number, route: string, name: string): void {
      const [routeInput, nameInput] = Array.from(rows()[index].querySelectorAll('input')) as HTMLInputElement[];
      type(routeInput, route); type(nameInput, name);
      fixture.detectChanges();
    }

    beforeEach(() => {
      component.model.update(value => ({ ...value, ...validAccess }));
      fixture.detectChanges();
    });

    it('shows an empty state until an opener is added', () => {
      expect(rows().length).toBe(0);
      expect(fixture.nativeElement.querySelector('.opener-empty')?.textContent).toContain('No approved openers');
      expect(addButton().type).toBe('button');
      addButton().click();
      fixture.detectChanges();
      expect(rows().length).toBe(1);
      expect(fixture.nativeElement.querySelector('.opener-empty')).toBeNull();
    });

    it('labels each row and sends the trimmed, whitespace-collapsed opener on submit', async () => {
      addButton().click();
      fixture.detectChanges();
      const [routeInput, nameInput] = Array.from(rows()[0].querySelectorAll('input')) as HTMLInputElement[];
      expect(routeInput.getAttribute('aria-label')).toBe('Route pattern 1');
      expect(nameInput.getAttribute('aria-label')).toBe('Visible name 1');
      fillRow(0, '  /projects/{id}/board ', '  Crear \n  tarea ');
      expect(submitButton().disabled).toBeFalse();
      await component.register();
      expect(api.createApplication).toHaveBeenCalledWith('project-1', jasmine.objectContaining({ approvedOpeners: [{ routePattern: '/projects/{id}/board', accessibleName: 'Crear tarea' }] }));
    });

    it('removes the selected row with a non-submitting button', async () => {
      addButton().click(); addButton().click();
      fixture.detectChanges();
      fillRow(0, '/first', 'First');
      fillRow(1, '/second', 'Second');
      const remove = rows()[0].querySelector('button') as HTMLButtonElement;
      expect(remove.type).toBe('button');
      expect(remove.getAttribute('aria-label')).toBe('Remove opener 1');
      remove.click();
      fixture.detectChanges();
      expect(rows().length).toBe(1);
      expect((rows()[0].querySelector('input') as HTMLInputElement).value).toBe('/second');
      await component.register();
      expect(api.createApplication).toHaveBeenCalledWith('project-1', jasmine.objectContaining({ approvedOpeners: [{ routePattern: '/second', accessibleName: 'Second' }] }));
    });

    for (const route of ['http://localhost:3000/projects', '/projects?tab=1', 'projects', '/a%2Fb', '/projects#new', '/' + 'a'.repeat(200)]) {
      it(`blocks submit and explains the error for the invalid route ${route.slice(0, 40)}`, async () => {
        addButton().click();
        fixture.detectChanges();
        fillRow(0, route, 'Crear tarea');
        const alert = fixture.nativeElement.querySelector('#opener-validation') as HTMLElement;
        expect(alert?.getAttribute('role')).toBe('alert');
        expect(rows()[0].querySelector('input')?.getAttribute('aria-describedby')).toBe('opener-validation');
        expect(rows()[0].querySelector('input')?.getAttribute('aria-invalid')).toBe('true');
        expect(submitButton().disabled).toBeTrue();
        await component.register();
        expect(api.createApplication).not.toHaveBeenCalled();
      });
    }

    for (const name of ['API key', 'Reset password', 'Cambiar contraseña', 'Clave de API', 'x'.repeat(121)]) {
      it(`blocks submit for the disallowed name ${name.slice(0, 20)}`, async () => {
        addButton().click();
        fixture.detectChanges();
        fillRow(0, '/projects/{id}/board', name);
        expect(fixture.nativeElement.querySelector('#opener-validation')?.getAttribute('role')).toBe('alert');
        expect(rows()[0].querySelectorAll('input')[1].getAttribute('aria-describedby')).toBe('opener-validation');
        expect(submitButton().disabled).toBeTrue();
        await component.register();
        expect(api.createApplication).not.toHaveBeenCalled();
      });
    }

    it('requires both fields once a row is partially filled', () => {
      addButton().click();
      fixture.detectChanges();
      fillRow(0, '/projects', '   ');
      expect(fixture.nativeElement.querySelector('#opener-validation')).not.toBeNull();
      expect(submitButton().disabled).toBeTrue();
    });

    it('drops fully empty rows on submit instead of failing on them', async () => {
      addButton().click(); addButton().click();
      fixture.detectChanges();
      fillRow(0, '   ', '  ');
      fillRow(1, '/projects/', 'Nuevo proyecto');
      expect(fixture.nativeElement.querySelector('#opener-validation')).toBeNull();
      expect(submitButton().disabled).toBeFalse();
      await component.register();
      expect(api.createApplication).toHaveBeenCalledWith('project-1', jasmine.objectContaining({ approvedOpeners: [{ routePattern: '/projects/', accessibleName: 'Nuevo proyecto' }] }));
    });

    it('disables adding at 50 openers', () => {
      component.model.update(value => ({ ...value, approvedOpeners: Array.from({ length: 49 }, (_, i) => ({ routePattern: `/page-${i}`, accessibleName: `Open ${i}` })) }));
      fixture.detectChanges();
      expect(addButton().disabled).toBeFalse();
      addButton().click();
      fixture.detectChanges();
      expect(rows().length).toBe(50);
      expect(addButton().disabled).toBeTrue();
      addButton().click();
      fixture.detectChanges();
      expect(rows().length).toBe(50);
    });

    it('describes the editor in Spanish', () => {
      component.localization.setLanguage('es');
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.opener-empty')?.textContent).toContain('Sin aperturas aprobadas');
      expect(addButton().textContent).toContain('Agregar apertura');
    });
  });

  describe('edit mode', () => {
    const existing: ApplicationResponse = { id: 'app-1', projectId: 'project-1', name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', maxCrawlDepth: 5, excludedRoutes: ['/admin'] };

    beforeEach(() => {
      routeUrl.push({ path: 'edit' }, { path: 'app-1' });
      routeParams['id'] = 'app-1';
      api.getApplication.and.resolveTo(existing);
    });

    it('loads the application by route id and hydrates the form without credentials', async () => {
      const edit = TestBed.createComponent(RegistrationComponent);
      edit.detectChanges();
      await edit.whenStable();
      edit.detectChanges();
      expect(api.getApplication).toHaveBeenCalledOnceWith('app-1');
      expect(edit.componentInstance.editMode).toBeTrue();
      expect(edit.componentInstance.model().name).toBe('Portal');
      expect(edit.componentInstance.model().maxCrawlDepth).toBe(5);
      expect(edit.componentInstance.model().excludedRoutes).toEqual(['/admin']);
      expect(edit.componentInstance.model().username).toBe('');
      expect(edit.componentInstance.model().password).toBe('');
    });

    it('does not allow submitting while the application is loading', async () => {
      let finish!: (value: ApplicationResponse) => void;
      api.getApplication.and.returnValue(new Promise(resolve => { finish = resolve; }));
      const edit = TestBed.createComponent(RegistrationComponent);
      edit.componentInstance.model.update(value => ({ ...value, username: 'tester', password: 'secret', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', name: 'Portal' }));
      edit.detectChanges();
      expect((edit.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBeTrue();
      await edit.componentInstance.register();
      expect(api.updateApplication).not.toHaveBeenCalled();
      finish(existing);
      await edit.whenStable();
      edit.detectChanges();
      expect((edit.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBeFalse();
    });

    it('shows a visible error instead of a blank form when the id is unknown', async () => {
      api.getApplication.and.rejectWith(new ApiError(404, 'Application not found'));
      const edit = TestBed.createComponent(RegistrationComponent);
      edit.detectChanges();
      await edit.whenStable();
      edit.detectChanges();
      expect(edit.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('could not be loaded');
      expect(edit.nativeElement.querySelector('form')).toBeNull();
    });

    it('updates by route id and never writes the retired application cache', async () => {
      const setItem = spyOn(Storage.prototype, 'setItem').and.callThrough();
      api.updateApplication.and.resolveTo(existing);
      const edit = TestBed.createComponent(RegistrationComponent);
      edit.detectChanges();
      await edit.whenStable();
      edit.componentInstance.model.update(value => ({ ...value, username: 'tester', password: 'secret' }));
      await edit.componentInstance.register();
      expect(api.updateApplication).toHaveBeenCalledWith('app-1', jasmine.objectContaining({ name: 'Portal', username: 'tester' }));
      expect(setItem.calls.allArgs().map(args => args[0])).not.toContain(['sgf', 'application'].join('.'));
    });

    it('sends approved openers back unchanged on update so editing never clears them', async () => {
      const approvedOpeners = [{ routePattern: '/projects/{id}/board', accessibleName: 'Crear tarea' }];
      api.getApplication.and.resolveTo({ ...existing, approvedOpeners });
      api.updateApplication.and.resolveTo({ ...existing, approvedOpeners });
      const edit = TestBed.createComponent(RegistrationComponent);
      edit.detectChanges();
      await edit.whenStable();
      edit.componentInstance.model.update(value => ({ ...value, username: 'tester', password: 'secret' }));
      await edit.componentInstance.register();
      expect(api.updateApplication).toHaveBeenCalledWith('app-1', jasmine.objectContaining({ approvedOpeners }));
    });

    it('renders loaded openers as editable rows and sends the edited list', async () => {
      const approvedOpeners = [{ routePattern: '/projects/{id}/board', accessibleName: 'Crear tarea' }, { routePattern: '/projects', accessibleName: 'Nuevo proyecto' }];
      api.getApplication.and.resolveTo({ ...existing, approvedOpeners });
      api.updateApplication.and.resolveTo(existing);
      const edit = TestBed.createComponent(RegistrationComponent);
      edit.detectChanges();
      await edit.whenStable();
      edit.detectChanges();
      const editRows = Array.from(edit.nativeElement.querySelectorAll('.opener-row')) as HTMLElement[];
      expect(editRows.length).toBe(2);
      const [routeInput, nameInput] = Array.from(editRows[0].querySelectorAll('input')) as HTMLInputElement[];
      expect(routeInput.value).toBe('/projects/{id}/board');
      expect(nameInput.value).toBe('Crear tarea');
      nameInput.value = 'Crear tarea nueva'; nameInput.dispatchEvent(new Event('input'));
      (editRows[1].querySelector('button') as HTMLButtonElement).click();
      edit.detectChanges();
      edit.componentInstance.model.update(value => ({ ...value, username: 'tester', password: 'secret' }));
      await edit.componentInstance.register();
      expect(api.updateApplication).toHaveBeenCalledWith('app-1', jasmine.objectContaining({ approvedOpeners: [{ routePattern: '/projects/{id}/board', accessibleName: 'Crear tarea nueva' }] }));
    });
  });

  it('keeps the created application in component state and does not write the retired cache after registering', async () => {
    const setItem = spyOn(Storage.prototype, 'setItem').and.callThrough();
    api.testAccess.and.resolveTo({ reachable: true, authenticated: true });
    component.model.update(value => ({ ...value, projectName: 'Workspace', name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', username: 'tester', password: 'secret' }));
    await component.register();
    expect(component.applicationId()).toBe('app-1');
    expect(api.testAccess).toHaveBeenCalledWith('app-1');
    expect(setItem.calls.allArgs().map(args => args[0])).not.toContain(['sgf', 'application'].join('.'));
  });

  describe('access test result text', () => {
    async function showAccessResult(result: Awaited<ReturnType<ApiService['testAccess']>>, language: 'en' | 'es'): Promise<string> {
      TestBed.inject(LocalizationService).setLanguage(language);
      api.testAccess.and.resolveTo(result);
      component.applicationId.set('app-1');
      component.model.update(value => ({ ...value, projectName: 'Workspace', name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login', username: 'tester', password: 'secret' }));
      await component.testAccess();
      fixture.detectChanges();
      return fixture.nativeElement.querySelector('.result-card p')?.textContent ?? '';
    }

    it('translates the backend result code instead of showing the English message', async () => {
      const rejected = { reachable: true, authenticated: false, message: 'Login rejected or still pending', code: 'NOT_AUTHENTICATED' as const };
      expect(await showAccessResult(rejected, 'es')).toContain('no aceptó');
      expect(await showAccessResult(rejected, 'es')).not.toContain('Login rejected');
      expect(await showAccessResult({ reachable: true, authenticated: true, message: 'Login successful', code: 'AUTHENTICATED' }, 'es')).toContain('aceptó las credenciales');
      expect(await showAccessResult({ reachable: false, authenticated: false, message: 'Browser login unavailable or failed', code: 'BROWSER_LOGIN_FAILED' }, 'es')).toContain('navegador');
      expect(await showAccessResult({ reachable: true, authenticated: false, message: 'Login form unavailable', code: 'LOGIN_FORM_UNAVAILABLE' }, 'es')).toContain('formulario');
      expect(await showAccessResult(rejected, 'en')).toContain('rejected');
    });

    it('falls back to the generic text, never a raw key, for a code without a translation', async () => {
      const unknown = { reachable: true, authenticated: false, message: 'Something new', code: 'SOMETHING_NEW' } as unknown as Awaited<ReturnType<ApiService['testAccess']>>;
      expect(await showAccessResult(unknown, 'es')).toBe('El sistema respondió, pero no se confirmó la autenticación.');
      expect(await showAccessResult({ ...unknown, authenticated: true }, 'en')).toBe('Credentials were accepted by the local system.');
    });

    it('falls back to the generic accepted or unconfirmed text when no code is present', async () => {
      expect(await showAccessResult({ reachable: true, authenticated: true }, 'es')).toContain('El sistema local aceptó las credenciales');
      expect(await showAccessResult({ reachable: true, authenticated: false }, 'en')).toContain('authentication was not confirmed');
    });
  });
});
