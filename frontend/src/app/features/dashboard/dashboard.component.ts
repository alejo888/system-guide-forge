import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService, AnalysisSummaryResponse, ApplicationResponse, LocalizationService } from '../../core/api.service';

type LoadState = 'loading' | 'ready' | 'error';
type StartState = 'starting' | 'error';
type DashboardAnalysis = AnalysisSummaryResponse & { applicationName: string };

@Component({
  selector: 'sgf-dashboard', standalone: true, imports: [RouterLink],
  templateUrl: './dashboard.component.html', styleUrl: './dashboard.component.css'
})
export class DashboardComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
      readonly localization = inject(LocalizationService);
      readonly t = (key: string): string => this.localization.t(key);
  readonly applications = signal<ApplicationResponse[]>([]);
  readonly analyses = signal<DashboardAnalysis[]>([]);
  readonly applicationsState = signal<LoadState>('loading');
  readonly historyState = signal<LoadState>('loading');
  readonly startStates = signal<Record<string, StartState>>({});
  readonly totalPages = computed(() => this.analyses().reduce((total, analysis) => total + analysis.pageCount, 0));

  ngOnInit(): void { void this.load(); }

  async startAnalysis(applicationId: string): Promise<void> {
    this.startStates.update(states => ({ ...states, [applicationId]: 'starting' }));
    try {
      const analysis = await this.api.startAnalysis(applicationId);
      await this.router.navigate(['/analysis', analysis.id]);
    } catch {
      this.startStates.update(states => ({ ...states, [applicationId]: 'error' }));
    }
  }

  private async load(): Promise<void> {
    let applications: ApplicationResponse[];
    try { applications = await this.api.listApplications(); }
    catch { this.applicationsState.set('error'); this.historyState.set('ready'); return; }
    this.applications.set(applications);
    this.applicationsState.set('ready');
    await this.loadHistory(applications);
  }

  private async loadHistory(applications: ApplicationResponse[]): Promise<void> {
    try {
      const histories = await Promise.all(applications.map(async application =>
        (await this.api.getApplicationAnalyses(application.id)).map(analysis => ({ ...analysis, applicationName: application.name }))));
      this.analyses.set(histories.flat().sort((left, right) => new Date(right.startedAt).getTime() - new Date(left.startedAt).getTime() || right.id.localeCompare(left.id)));
      this.historyState.set('ready');
    } catch {
      this.historyState.set('error');
    }
  }
}
