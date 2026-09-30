import { Component, computed, inject, signal } from '@angular/core';
import { form, FormField, required } from '@angular/forms/signals';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService, AccessTestResult, ApplicationInput, ApplicationResponse, LocalizationService } from '../../core/api.service';

type RegistrationState = 'idle' | 'saving' | 'testing' | 'success' | 'starting-analysis' | 'error';

@Component({
  selector: 'sgf-registration', standalone: true, imports: [FormField, RouterLink],
  templateUrl: './registration.component.html', styleUrl: './registration.component.css'
})
export class RegistrationComponent {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
      readonly localization = inject(LocalizationService);
      readonly t = (key: string): string => this.localization.t(key);
  private readonly route = inject(ActivatedRoute);
  readonly editMode = this.route.snapshot.url.some(segment => segment.path === 'edit');
  readonly routeApplicationId = this.route.snapshot.paramMap.get('id') ?? '';
  readonly model = signal<ApplicationInput & { projectName: string }>({
    projectName: '', name: '', baseUrl: '', loginUrl: '', username: '', password: '', maxCrawlDepth: 0, excludedRoutes: []
  });
  readonly registrationForm = form(this.model, path => {
    required(path.projectName); required(path.name); required(path.baseUrl); required(path.loginUrl); required(path.username); required(path.password);
  });
  readonly state = signal<RegistrationState>('idle');
  readonly applicationLoadState = signal<'loading' | 'ready' | 'error'>(this.editMode ? 'loading' : 'ready');
  readonly passwordVisible = signal(false);
  readonly loadingMessage = computed(() => {
    const spanish = this.localization.language() === 'es';
    if (this.applicationLoadState() === 'loading') return this.t('loading-application');
    if (this.state() === 'saving') return spanish ? 'Guardando la configuración…' : 'Saving configuration…';
    if (this.state() === 'testing') return spanish ? 'Probando el acceso…' : 'Testing access…';
    return '';
  });
  readonly errorMessage = signal('');
  readonly accessResult = signal<AccessTestResult | null>(null);
  readonly applicationId = signal(this.routeApplicationId);
  readonly localUrlsValid = computed(() => this.isLocalUrl(this.model().baseUrl) && this.isLocalUrl(this.model().loginUrl));
  readonly crawlerConfigurationValid = computed(() => {
    const { maxCrawlDepth = 0, excludedRoutes = [] } = this.model();
    return Number.isInteger(maxCrawlDepth) && maxCrawlDepth >= 0 && maxCrawlDepth <= 5 && excludedRoutes.every(route => this.isExcludedRoute(route));
  });

  constructor() { if (this.editMode) void this.loadApplication(); }

  isLocalUrl(value: string): boolean {
    try { const url = new URL(value); return (url.protocol === 'http:' || url.protocol === 'https:') && (url.hostname === 'localhost' || url.hostname === '127.0.0.1' || url.hostname === '[::1]'); }
    catch { return false; }
  }

  setMaxCrawlDepth(value: string): void { this.model.update(model => ({ ...model, maxCrawlDepth: Number(value) })); }
  setExcludedRoutes(value: string): void {
    const excludedRoutes = value.split(/[\n,]/).map(route => route.trim()).filter(Boolean);
    this.model.update(model => ({ ...model, excludedRoutes }));
  }
  isExcludedRoute(value: string): boolean { return /^\/(?:[^/?#%]+(?:\/[^/?#%]+)*)?\/?$/.test(value); }
  routeIsExcluded(route: string, excludedRoute: string): boolean { return route === excludedRoute || route.startsWith(`${excludedRoute}/`); }
      private normalizeExcludedRoutes(routes: string[]): string[] { return [...new Set(routes.map(route => route.length > 1 && route.endsWith('/') ? route.slice(0, -1) : route))]; }

  async register(): Promise<void> {
    if (this.applicationLoadState() !== 'ready' || !this.localUrlsValid() || !this.crawlerConfigurationValid() || this.registrationForm().invalid()) return;
    this.state.set('saving'); this.errorMessage.set('');
    try {
      const value = this.model();
      const { projectName: _, ...application } = value;
      application.maxCrawlDepth ??= 0; application.excludedRoutes = this.normalizeExcludedRoutes(application.excludedRoutes ?? []);
      const saved = this.editMode
        ? await this.api.updateApplication(this.applicationId(), application)
        : await this.createApplication(value.projectName, application);
      if (this.editMode) { this.state.set('success'); await this.router.navigate(['/']); }
      else { this.applicationId.set(saved.id); await this.testAccess(); }
    } catch { this.fail(this.t(this.editMode ? 'update-error' : 'register-error'));  }
  }

  /** The translated text for the backend result code; without a code the generic accepted/unconfirmed text is used. */
  accessText(access: AccessTestResult): string {
    if (access.code) return this.t('access-code-' + access.code.toLowerCase().replace(/_/g, '-'));
    return this.t(access.authenticated ? 'access-accepted' : 'access-unconfirmed');
  }

  async testAccess(): Promise<void> {
    if (!this.applicationId()) return;
    this.state.set('testing');
    try { this.accessResult.set(await this.api.testAccess(this.applicationId())); this.state.set('success'); }
    catch { this.fail(this.t('access-error')); }
  }
  async startAnalysis(): Promise<void> {
    if (!this.applicationId()) return;
    this.state.set('starting-analysis');
    try { const analysis = await this.api.startAnalysis(this.applicationId()); await this.router.navigate(['/analysis', analysis.id]); }
    catch { this.fail(this.t('start-analysis-retry')); }
  }
  private async createApplication(projectName: string, application: ApplicationInput): Promise<ApplicationResponse> {
    const project = await this.api.createProject({ name: projectName.trim() }); return this.api.createApplication(project.id, application);
  }
  private async loadApplication(): Promise<void> {
    try {
      const application = await this.api.getApplication(this.routeApplicationId);
      this.model.update(model => ({ ...model, projectName: application.name, name: application.name, baseUrl: application.baseUrl, loginUrl: application.loginUrl, maxCrawlDepth: application.maxCrawlDepth ?? 0, excludedRoutes: application.excludedRoutes ?? [] }));
      this.applicationLoadState.set('ready');
    } catch { this.applicationLoadState.set('error'); }
  }
  private fail(message: string): void { this.state.set('error'); this.errorMessage.set(message); }
}
