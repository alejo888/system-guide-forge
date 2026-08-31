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
  readonly existingApplication = this.readApplication();
  readonly model = signal<ApplicationInput & { projectName: string }>({
    projectName: this.editMode ? (this.existingApplication?.name ?? '') : '',
    name: this.editMode ? (this.existingApplication?.name ?? '') : '',
    baseUrl: this.editMode ? (this.existingApplication?.baseUrl ?? '') : '',
    loginUrl: this.editMode ? (this.existingApplication?.loginUrl ?? '') : '', username: '', password: '',
    maxCrawlDepth: this.existingApplication?.maxCrawlDepth ?? 2,
    excludedRoutes: this.existingApplication?.excludedRoutes ?? []
  });
  readonly registrationForm = form(this.model, path => {
    required(path.projectName); required(path.name); required(path.baseUrl); required(path.loginUrl); required(path.username); required(path.password);
  });
  readonly state = signal<RegistrationState>('idle');
  readonly errorMessage = signal('');
  readonly accessResult = signal<AccessTestResult | null>(null);
  readonly applicationId = signal(this.existingApplication?.id ?? '');
  readonly localUrlsValid = computed(() => this.isLocalUrl(this.model().baseUrl) && this.isLocalUrl(this.model().loginUrl));
  readonly crawlerConfigurationValid = computed(() => {
    const { maxCrawlDepth = 2, excludedRoutes = [] } = this.model();
    return Number.isInteger(maxCrawlDepth) && maxCrawlDepth >= 0 && maxCrawlDepth <= 5 && excludedRoutes.every(route => this.isExcludedRoute(route));
  });

  isLocalUrl(value: string): boolean {
    try { const url = new URL(value); return (url.protocol === 'http:' || url.protocol === 'https:') && (url.hostname === 'localhost' || url.hostname === '127.0.0.1'); }
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
    if (!this.localUrlsValid() || !this.crawlerConfigurationValid() || this.registrationForm().invalid()) return;
    this.state.set('saving'); this.errorMessage.set('');
    try {
      const value = this.model();
      const { projectName: _, ...application } = value;
      application.maxCrawlDepth ??= 2; application.excludedRoutes = this.normalizeExcludedRoutes(application.excludedRoutes ?? []);
      const saved = this.editMode
        ? await this.api.updateApplication(this.applicationId(), application)
        : await this.createApplication(value.projectName, application);
      localStorage.setItem('sgf.application', JSON.stringify(saved));
      if (this.editMode) { this.state.set('success'); await this.router.navigate(['/']); }
      else { this.applicationId.set(saved.id); await this.testAccess(); }
    } catch { this.fail(this.t(this.editMode ? 'update-error' : 'register-error'));  }
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
  private readApplication(): ApplicationResponse | null {
    try { const value = localStorage.getItem('sgf.application'); return value ? JSON.parse(value) as ApplicationResponse : null; } catch { return null; }
  }
  private fail(message: string): void { this.state.set('error'); this.errorMessage.set(message); }
}
