import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService, ApplicationResponse } from '../../core/api.service';

@Component({
  selector: 'sgf-dashboard', standalone: true, imports: [RouterLink],
  templateUrl: './dashboard.component.html', styleUrl: './dashboard.component.css'
})
export class DashboardComponent {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  readonly application = signal<ApplicationResponse | null>(this.readApplication());
  readonly state = signal<'idle' | 'starting' | 'error'>('idle');
  readonly errorMessage = signal('');

  async startAnalysis(): Promise<void> {
    const application = this.application();
    if (!application) return;
    this.state.set('starting');
    this.errorMessage.set('');
    try {
      const analysis = await this.api.startAnalysis(application.id);
      await this.router.navigate(['/analysis', analysis.id]);
    } catch {
      this.state.set('error');
      this.errorMessage.set('The analysis could not be started. Check that the API and local system are available.');
    }
  }

  private readApplication(): ApplicationResponse | null {
    try { const value = localStorage.getItem('sgf.application'); return value ? JSON.parse(value) as ApplicationResponse : null; }
    catch { return null; }
  }
}
