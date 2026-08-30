import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService, AnalysisSummaryResponse, ApplicationResponse } from '../../core/api.service';

type StartState = 'idle' | 'starting' | 'error';
type HistoryState = 'loading' | 'ready' | 'error';

@Component({
  selector: 'sgf-dashboard', standalone: true, imports: [RouterLink],
  templateUrl: './dashboard.component.html', styleUrl: './dashboard.component.css'
})
export class DashboardComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  readonly application = signal<ApplicationResponse | null>(this.readApplication());
  readonly analyses = signal<AnalysisSummaryResponse[]>([]);
  readonly state = signal<StartState>('idle');
  readonly historyState = signal<HistoryState>('loading');
  readonly errorMessage = signal('');
  readonly historyErrorMessage = signal('');
  readonly totalPages = computed(() => this.analyses().reduce((total, analysis) => total + analysis.pageCount, 0));

  ngOnInit(): void {
    const application = this.application();
    if (!application) { this.historyState.set('ready'); return; }
    void this.loadHistory(application.id);
  }

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

  private async loadHistory(applicationId: string): Promise<void> {
    try {
      const analyses = await this.api.getApplicationAnalyses(applicationId);
      this.analyses.set([...analyses].sort((left, right) => new Date(right.startedAt).getTime() - new Date(left.startedAt).getTime() || right.id.localeCompare(left.id)));
      this.historyState.set('ready');
    } catch {
      this.historyState.set('error');
      this.historyErrorMessage.set('Analysis history could not be loaded. Check that the API is running and try again.');
    }
  }

  private readApplication(): ApplicationResponse | null {
    try {
      const value: unknown = JSON.parse(localStorage.getItem('sgf.application') ?? 'null');
      return this.isApplicationResponse(value) ? value : null;
    } catch { return null; }
  }

  private isApplicationResponse(value: unknown): value is ApplicationResponse {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
    const application = value as Record<string, unknown>;
    return ['id', 'projectId', 'name', 'baseUrl', 'loginUrl'].every(field => typeof application[field] === 'string' && application[field]);
  }
}
