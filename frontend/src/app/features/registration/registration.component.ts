import { Component, computed, inject, signal } from '@angular/core';
import { form, FormField, required } from '@angular/forms/signals';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService, AccessTestResult, ApplicationInput, ApplicationResponse } from '../../core/api.service';

type RegistrationState = 'idle' | 'saving' | 'testing' | 'success' | 'starting-analysis' | 'error';

@Component({
  selector: 'sgf-registration', standalone: true, imports: [FormField, RouterLink],
  templateUrl: './registration.component.html', styleUrl: './registration.component.css'
})
export class RegistrationComponent {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly editMode = this.route.snapshot.url.some(segment => segment.path === 'edit');
  readonly existingApplication = this.readApplication();
  readonly model = signal<ApplicationInput & { projectName: string }>({
    projectName: this.editMode ? (this.existingApplication?.name ?? '') : '',
    name: this.editMode ? (this.existingApplication?.name ?? '') : '',
    baseUrl: this.editMode ? (this.existingApplication?.baseUrl ?? '') : '',
    loginUrl: this.editMode ? (this.existingApplication?.loginUrl ?? '') : '', username: '', password: ''
  });
  readonly registrationForm = form(this.model, path => {
    required(path.projectName); required(path.name); required(path.baseUrl); required(path.loginUrl); required(path.username); required(path.password);
  });
  readonly state = signal<RegistrationState>('idle');
  readonly errorMessage = signal('');
  readonly accessResult = signal<AccessTestResult | null>(null);
  readonly applicationId = signal(this.existingApplication?.id ?? '');
  readonly localUrlsValid = computed(() => this.isLocalUrl(this.model().baseUrl) && this.isLocalUrl(this.model().loginUrl));

  isLocalUrl(value: string): boolean {
    try { const url = new URL(value); return (url.protocol === 'http:' || url.protocol === 'https:') && (url.hostname === 'localhost' || url.hostname === '127.0.0.1'); }
    catch { return false; }
  }

  async register(): Promise<void> {
    if (!this.localUrlsValid() || this.registrationForm().invalid()) return;
    this.state.set('saving'); this.errorMessage.set('');
    try {
      const value = this.model();
      const { projectName: _, ...application } = value;
      const saved = this.editMode
        ? await this.api.updateApplication(this.applicationId(), application)
        : await this.createApplication(value.projectName, application);
      localStorage.setItem('sgf.application', JSON.stringify(saved));
      if (this.editMode) {
        this.state.set('success');
        await this.router.navigate(['/']);
      } else {
        this.applicationId.set(saved.id); await this.testAccess();
      }
    } catch { this.fail(this.editMode ? 'We could not update the system. Check that the API is running and try again.' : 'We could not create the project. Check that the API is running and try again.'); }
  }

  async testAccess(): Promise<void> {
    if (!this.applicationId()) return;
    this.state.set('testing');
    try { this.accessResult.set(await this.api.testAccess(this.applicationId())); this.state.set('success'); }
    catch { this.fail('The access test could not be completed. The system remains safely untested.'); }
  }

  async startAnalysis(): Promise<void> {
    if (!this.applicationId()) return;
    this.state.set('starting-analysis');
    try { const analysis = await this.api.startAnalysis(this.applicationId()); await this.router.navigate(['/analysis', analysis.id]); }
    catch { this.fail('The analysis could not be started. Try again when the system is available.'); }
  }

  private async createApplication(projectName: string, application: ApplicationInput): Promise<ApplicationResponse> {
    const project = await this.api.createProject({ name: projectName.trim() });
    return this.api.createApplication(project.id, application);
  }
  private readApplication(): ApplicationResponse | null {
    try { const value = localStorage.getItem('sgf.application'); return value ? JSON.parse(value) as ApplicationResponse : null; }
    catch { return null; }
  }
  private fail(message: string): void { this.state.set('error'); this.errorMessage.set(message); }
}
