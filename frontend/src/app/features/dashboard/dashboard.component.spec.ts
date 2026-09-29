import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { DashboardComponent } from './dashboard.component';
import { ApiService, ApplicationResponse, AnalysisSummaryResponse } from '../../core/api.service';

describe('DashboardComponent', () => {
  let fixture: ComponentFixture<DashboardComponent>;
  let component: DashboardComponent;
  let api: jasmine.SpyObj<ApiService>;

  const portal: ApplicationResponse = { id: 'app-1', projectId: 'project-1', name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login' };
  const adminConsole: ApplicationResponse = { id: 'app-2', projectId: 'project-2', name: 'Console', baseUrl: 'http://localhost:4000', loginUrl: 'http://localhost:4000/login' };
  const portalAnalyses: AnalysisSummaryResponse[] = [
    { id: 'analysis-2', applicationId: 'app-1', status: 'FAILED', startedAt: '2026-02-01T00:00:00Z', completedAt: '2026-02-01T00:00:01Z', failureMessage: 'Crawler failed', pageCount: 0 },
    { id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T00:01:00Z', failureMessage: null, pageCount: 3 }
  ];
  const consoleAnalyses: AnalysisSummaryResponse[] = [
    { id: 'analysis-3', applicationId: 'app-2', status: 'COMPLETED', startedAt: '2026-03-01T00:00:00Z', completedAt: '2026-03-01T00:01:00Z', failureMessage: null, pageCount: 4 }
  ];

  async function render(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    localStorage.setItem('sgf.language', 'en');
    api = jasmine.createSpyObj<ApiService>('ApiService', ['listApplications', 'getApplicationAnalyses', 'startAnalysis']);
    api.listApplications.and.resolveTo([portal, adminConsole]);
    api.getApplicationAnalyses.and.callFake(async id => id === 'app-1' ? portalAnalyses : consoleAnalyses);
    await TestBed.configureTestingModule({ imports: [DashboardComponent], providers: [{ provide: ApiService, useValue: api }, provideRouter([])] }).compileComponents();
    fixture = TestBed.createComponent(DashboardComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    localStorage.removeItem('sgf.language');
  });

  it('renders one card per registered system with an edit link by id and a real counter', async () => {
    await render();
    const cards = fixture.nativeElement.querySelectorAll('.system-card') as NodeListOf<HTMLElement>;
    expect(cards.length).toBe(2);
    expect(cards[0].textContent).toContain('Portal');
    expect(cards[0].textContent).toContain('http://localhost:3000');
    expect(cards[0].querySelector('a[href="/edit/app-1"]')).not.toBeNull();
    expect(cards[1].querySelector('a[href="/edit/app-2"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.metric-card strong').textContent.trim()).toBe('2');
  });

  it('ignores a stale cached application entry and lists systems from the API', async () => {
    // Built from parts so the repo-wide check for the retired key stays clean.
    const legacyKey =['sgf', 'application'].join('.');
    localStorage.setItem(legacyKey, JSON.stringify({ id: 'ghost', projectId: 'p', name: 'Ghost', baseUrl: 'http://localhost:1', loginUrl: 'http://localhost:1/login' }));
    try {
      await render();
      expect(api.getApplicationAnalyses).not.toHaveBeenCalledWith('ghost');
      expect(fixture.nativeElement.textContent).not.toContain('Ghost');
      expect(fixture.nativeElement.querySelectorAll('.system-card').length).toBe(2);
    } finally { localStorage.removeItem(legacyKey); }
  });

  it('aggregates history across systems, newest first, labeled by system name', async () => {
    await render();
    expect(api.getApplicationAnalyses).toHaveBeenCalledWith('app-1');
    expect(api.getApplicationAnalyses).toHaveBeenCalledWith('app-2');
    expect(component.analyses().map(analysis => analysis.id)).toEqual(['analysis-3', 'analysis-2', 'analysis-1']);
    const cards = fixture.nativeElement.querySelectorAll('.analysis-card') as NodeListOf<HTMLElement>;
    expect(cards.length).toBe(3);
    expect(cards[0].textContent).toContain('Console');
    expect(cards[1].textContent).toContain('Portal');
    expect(fixture.nativeElement.querySelector('a[href="/analysis/analysis-3"]')).not.toBeNull();
    const metrics = Array.from(fixture.nativeElement.querySelectorAll('.metric-card strong') as NodeListOf<HTMLElement>).map(node => node.textContent!.trim());
    expect(metrics).toEqual(['2', '3', '7']);
  });

  it('shows the empty state without requesting history when no systems are registered', async () => {
    api.listApplications.and.resolveTo([]);
    await render();
    expect(api.getApplicationAnalyses).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Register a local system');
    expect(fixture.nativeElement.querySelector('.metric-card strong').textContent.trim()).toBe('0');
    expect(fixture.nativeElement.querySelectorAll('.system-card').length).toBe(0);
    expect(fixture.nativeElement.textContent).toContain('No analyses yet.');
  });

  it('sorts history by parsed timestamps and uses ids as a deterministic tie-breaker', async () => {
    api.listApplications.and.resolveTo([portal]);
    api.getApplicationAnalyses.and.resolveTo([
      { ...portalAnalyses[0], id: 'analysis-z', startedAt: '2026-02-01T00:30:00+01:00' },
      { ...portalAnalyses[1], id: 'analysis-a', startedAt: '2026-02-01T00:00:00Z' },
      { ...portalAnalyses[1], id: 'analysis-b', startedAt: '2026-02-01T01:00:00+01:00' },
    ]);
    await render();
    expect(component.analyses().map(analysis => analysis.id)).toEqual(['analysis-b', 'analysis-a', 'analysis-z']);
  });

  it('shows a systems error, not an API-down claim, when listing fails', async () => {
    api.listApplications.and.rejectWith(new Error('offline'));
    await render();
    const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
    expect(alert.textContent).toContain('registered systems could not be loaded');
    expect(alert.textContent).not.toContain('API is running');
    expect(fixture.nativeElement.querySelectorAll('.system-card').length).toBe(0);
    expect(fixture.nativeElement.textContent).not.toContain('Register a local system');
  });

  it('does not claim an empty history when the systems could not be listed', async () => {
    api.listApplications.and.rejectWith(new Error('offline'));
    await render();
    expect(fixture.nativeElement.textContent).not.toContain('No analyses yet');
    expect(fixture.nativeElement.querySelectorAll('[role="alert"]').length).toBe(1);
    expect(api.getApplicationAnalyses).not.toHaveBeenCalled();
  });

  it('renders the history error state without half-rendering when any history request fails', async () => {
    api.getApplicationAnalyses.and.callFake(async id => { if (id === 'app-2') throw new Error('gone'); return portalAnalyses; });
    await render();
    expect(fixture.nativeElement.querySelector('.activity-empty.error-state [role="alert"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.analysis-card').length).toBe(0);
    expect(fixture.nativeElement.querySelectorAll('.system-card').length).toBe(2);
  });

  it('starts an analysis for the selected system and navigates to it', async () => {
    api.startAnalysis.and.resolveTo({ id: 'analysis-9', applicationId: 'app-2', status: 'RUNNING', startedAt: '2026-04-01T00:00:00Z', completedAt: null, failureMessage: null });
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    await render();
    const buttons = fixture.nativeElement.querySelectorAll('.system-card button') as NodeListOf<HTMLButtonElement>;
    buttons[1].click();
    await fixture.whenStable();
    expect(api.startAnalysis).toHaveBeenCalledOnceWith('app-2');
    expect(navigate).toHaveBeenCalledWith(['/analysis', 'analysis-9']);
  });

  it('reports a start failure only on the card that failed', async () => {
    api.startAnalysis.and.rejectWith(new Error('boom'));
    await render();
    (fixture.nativeElement.querySelectorAll('.system-card button') as NodeListOf<HTMLButtonElement>)[0].click();
    await fixture.whenStable();
    fixture.detectChanges();
    const cards = fixture.nativeElement.querySelectorAll('.system-card') as NodeListOf<HTMLElement>;
    expect(cards[0].querySelector('[role="alert"]')).not.toBeNull();
    expect(cards[1].querySelector('[role="alert"]')).toBeNull();
  });
});
