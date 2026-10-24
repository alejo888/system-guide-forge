import { Component, computed, inject, signal } from '@angular/core';
import { form, FormField, required } from '@angular/forms/signals';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService, AccessTestResult, ApplicationInput, ApplicationResponse, ApprovedOpener, LocalizationService } from '../../core/api.service';

type RegistrationState = 'idle' | 'saving' | 'testing' | 'success' | 'starting-analysis' | 'error';

/** Mirrors the backend limits in ProjectApplicationService for RF24 approved openers. */
const MAX_APPROVED_OPENERS = 50;
const MAX_OPENER_ROUTE_LENGTH = 200;
const MAX_OPENER_NAME_LENGTH = 120;
const OPENER_ROUTE = /^\/(?:[^/?#]+(?:\/[^/?#]+)*)?\/?$/;
/** Mirrors FunctionalModuleDeriver.ID_SEGMENT: a purely numeric or UUID segment is a record identifier. */
const ID_SEGMENT = /^(?:[0-9]+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$/;
/** Mirrors LocalUrlValidator.DOCKER_HOST: accepted only when the backend reports allowDockerHost. */
const DOCKER_HOST = 'host.docker.internal';
const SENSITIVE_OPENER_NAME = /password|api[-_ ]?key|secret|token|credential|credencial|contrase(?:n|ñ)a|clave (?:de )?api/;

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
    projectName: '', name: '', baseUrl: '', loginUrl: '', username: '', password: '', maxCrawlDepth: 0, excludedRoutes: [], approvedOpeners: []
  });
  readonly registrationForm = form(this.model, path => {
    required(path.projectName); required(path.name); required(path.baseUrl); required(path.loginUrl); required(path.username); required(path.password);
  });
  readonly state = signal<RegistrationState>('idle');
  readonly applicationLoadState = signal<'loading' | 'ready' | 'error'>(this.editMode ? 'loading' : 'ready');
  readonly passwordVisible = signal(false);
  /** Stays false (localhost-only) until GET /api/config confirms the backend allows the Docker host. */
  readonly allowDockerHost = signal(false);
  readonly localUrlValidationKey = computed(() => this.allowDockerHost() ? 'local-url-validation-docker' : 'local-url-validation');
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
  readonly maxApprovedOpeners = MAX_APPROVED_OPENERS;
  readonly openerRoutePlaceholder = '/projects/{id}/board';
  /** Per-row validity; a fully empty row is valid because it is dropped on submit. */
  readonly openerRowsValid = computed(() => (this.model().approvedOpeners ?? []).map(opener => this.isOpenerRowValid(opener)));
  /** Per-row duplicate flag: true for a valid row whose server key matches an earlier valid row; the first occurrence stays unflagged. */
  readonly openerRowsDuplicate = computed(() => {
    const seen = new Set<string>();
    return (this.model().approvedOpeners ?? []).map((opener, i) => {
      if (this.isOpenerEmpty(opener) || !this.openerRowsValid()[i]) return false;
      const key = this.openerKey(opener);
      if (seen.has(key)) return true;
      seen.add(key);
      return false;
    });
  });
  readonly hasDuplicateOpeners = computed(() => this.openerRowsDuplicate().some(Boolean));
  /** True when a row breaks the route or name rules, or there are too many rows; duplicates are reported separately. */
  readonly openerRulesBroken = computed(() => this.openerRowsValid().length > MAX_APPROVED_OPENERS || !this.openerRowsValid().every(Boolean));
  readonly approvedOpenersValid = computed(() => !this.openerRulesBroken() && !this.hasDuplicateOpeners());

  /** Settles (never rejects) once GET /api/config has been answered or has failed. */
  private readonly configLoaded = this.loadConfig();

  constructor() { if (this.editMode) void this.loadApplication(); }

  isLocalUrl(value: string): boolean {
    try {
      const url = new URL(value);
      if (url.protocol !== 'http:' && url.protocol !== 'https:') return false;
      if (this.allowDockerHost() && url.hostname === DOCKER_HOST) return true;
      return url.hostname === 'localhost' || url.hostname === '127.0.0.1' || url.hostname === '[::1]';
    }
    catch { return false; }
  }

  setMaxCrawlDepth(value: string): void { this.model.update(model => ({ ...model, maxCrawlDepth: Number(value) })); }
  setExcludedRoutes(value: string): void {
    const excludedRoutes = value.split(/[\n,]/).map(route => route.trim()).filter(Boolean);
    this.model.update(model => ({ ...model, excludedRoutes }));
  }
  isExcludedRoute(value: string): boolean { return /^\/(?:[^/?#%]+(?:\/[^/?#%]+)*)?\/?$/.test(value); }
  addOpener(): void {
    if ((this.model().approvedOpeners ?? []).length >= MAX_APPROVED_OPENERS) return;
    this.model.update(model => ({ ...model, approvedOpeners: [...(model.approvedOpeners ?? []), { routePattern: '', accessibleName: '' }] }));
  }
  removeOpener(index: number): void {
    this.model.update(model => ({ ...model, approvedOpeners: (model.approvedOpeners ?? []).filter((_, i) => i !== index) }));
  }
  setOpener(index: number, field: keyof ApprovedOpener, value: string): void {
    this.model.update(model => ({ ...model, approvedOpeners: (model.approvedOpeners ?? []).map((opener, i) => i === index ? { ...opener, [field]: value } : opener) }));
  }
  private normalizeOpenerName(value: string): string { return value.normalize('NFC').replace(/\s+/g, ' ').trim(); }
  private isOpenerEmpty(opener: ApprovedOpener): boolean { return !opener.routePattern.trim() && !this.normalizeOpenerName(opener.accessibleName); }
  private isOpenerRowValid(opener: ApprovedOpener): boolean {
    if (this.isOpenerEmpty(opener)) return true;
    const route = opener.routePattern.trim();
    const name = this.normalizeOpenerName(opener.accessibleName);
    return route.length <= MAX_OPENER_ROUTE_LENGTH && !route.includes('%') && OPENER_ROUTE.test(route)
      && name.length >= 1 && name.length <= MAX_OPENER_NAME_LENGTH && !SENSITIVE_OPENER_NAME.test(name.toLowerCase());
  }
  /**
   * The key ProjectApplicationService.openerKey merges on: the RF23 route template (a literal {id} segment, numeric and
   * UUID segments all become {id}; no trailing slash except for /) plus the NFC, whitespace-collapsed, lower-case name.
   */
  private openerKey(opener: ApprovedOpener): string {
    const segments = opener.routePattern.trim().replace(/\/\{id\}(?=\/|$)/g, '/0').split('/').map(segment => ID_SEGMENT.test(segment) ? '{id}' : segment);
    const template = segments.join('/');
    const route = template.length > 1 && template.endsWith('/') ? template.slice(0, -1) : template;
    return route + '\n' + this.normalizeOpenerName(opener.accessibleName).toLowerCase();
  }
  private normalizeOpeners(openers: ApprovedOpener[]): ApprovedOpener[] {
    return openers.filter(opener => !this.isOpenerEmpty(opener)).map(opener => ({ routePattern: opener.routePattern.trim(), accessibleName: this.normalizeOpenerName(opener.accessibleName) }));
  }
  routeIsExcluded(route: string, excludedRoute: string): boolean { return route === excludedRoute || route.startsWith(`${excludedRoute}/`); }
      private normalizeExcludedRoutes(routes: string[]): string[] { return [...new Set(routes.map(route => route.length > 1 && route.endsWith('/') ? route.slice(0, -1) : route))]; }

  async register(): Promise<void> {
    if (this.applicationLoadState() !== 'ready' || !this.localUrlsValid() || !this.crawlerConfigurationValid() || !this.approvedOpenersValid() || this.registrationForm().invalid()) return;
    this.state.set('saving'); this.errorMessage.set('');
    try {
      const value = this.model();
      const { projectName: _, ...application } = value;
      application.maxCrawlDepth ??= 0; application.excludedRoutes = this.normalizeExcludedRoutes(application.excludedRoutes ?? []);
      application.approvedOpeners = this.normalizeOpeners(application.approvedOpeners ?? []);
      const saved = this.editMode
        ? await this.api.updateApplication(this.applicationId(), application)
        : await this.createApplication(value.projectName, application);
      if (this.editMode) { this.state.set('success'); await this.router.navigate(['/']); }
      else { this.applicationId.set(saved.id); await this.testAccess(); }
    } catch { this.fail(this.t(this.editMode ? 'update-error' : 'register-error'));  }
  }

  /** The translated text for the backend result code; without a code the generic accepted/unconfirmed text is used. */
  accessText(access: AccessTestResult): string {
    const key = access.code ? 'access-code-' + access.code.toLowerCase().replace(/_/g, '-') : null;
    if (key && this.localization.has(key)) return this.t(key);
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
  private async loadConfig(): Promise<void> {
    try { this.allowDockerHost.set((await this.api.getConfig())?.allowDockerHost === true); }
    catch { this.allowDockerHost.set(false); }
  }
  private async loadApplication(): Promise<void> {
    try {
      // Hydrate only after the config settles so a host.docker.internal URL is never judged against the localhost-only default.
      const [application] = await Promise.all([this.api.getApplication(this.routeApplicationId), this.configLoaded]);
      this.model.update(model => ({ ...model, projectName: application.name, name: application.name, baseUrl: application.baseUrl, loginUrl: application.loginUrl, maxCrawlDepth: application.maxCrawlDepth ?? 0, excludedRoutes: application.excludedRoutes ?? [],
        approvedOpeners: (application.approvedOpeners ?? []).map(opener => ({ ...opener })) }));
      this.applicationLoadState.set('ready');
    } catch { this.applicationLoadState.set('error'); }
  }
  private fail(message: string): void { this.state.set('error'); this.errorMessage.set(message); }
}
