import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { DashboardComponent } from './dashboard.component';
import { ApiService, ApplicationResponse, AnalysisSummaryResponse } from '../../core/api.service';

describe('DashboardComponent', () => {
  let fixture: ComponentFixture<DashboardComponent>;
  let component: DashboardComponent;
  let api: jasmine.SpyObj<ApiService>;

  const application: ApplicationResponse = { id: 'app-1', projectId: 'project-1', name: 'Portal', baseUrl: 'http://localhost:3000', loginUrl: 'http://localhost:3000/login' };
  const analyses: AnalysisSummaryResponse[] = [
    { id: 'analysis-2', applicationId: 'app-1', status: 'FAILED', startedAt: '2026-02-01T00:00:00Z', completedAt: '2026-02-01T00:00:01Z', failureMessage: 'Crawler failed', pageCount: 0 },
    { id: 'analysis-1', applicationId: 'app-1', status: 'COMPLETED', startedAt: '2026-01-01T00:00:00Z', completedAt: '2026-01-01T00:01:00Z', failureMessage: null, pageCount: 3 }
  ];

  beforeEach(async () => {
    localStorage.setItem('sgf.application', JSON.stringify(application));
    api = jasmine.createSpyObj<ApiService>('ApiService', ['getApplicationAnalyses', 'startAnalysis']);
    api.getApplicationAnalyses.and.resolveTo(analyses);
    await TestBed.configureTestingModule({ imports: [DashboardComponent], providers: [{ provide: ApiService, useValue: api }, provideRouter([])] }).compileComponents();
    fixture = TestBed.createComponent(DashboardComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => localStorage.removeItem('sgf.application'));

  it('loads history on initialization and renders aggregate metrics and links', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.getApplicationAnalyses).toHaveBeenCalledWith('app-1');
    expect(fixture.nativeElement.textContent).toContain('2');
    expect(fixture.nativeElement.textContent).toContain('3');
    expect(fixture.nativeElement.querySelectorAll('.analysis-card').length).toBe(2);
    expect(fixture.nativeElement.querySelector('a[href="/analysis/analysis-2"]')).not.toBeNull();
  });

  it('keeps an empty history safe when no application is selected', async () => {
    localStorage.removeItem('sgf.application');
    const emptyFixture = TestBed.createComponent(DashboardComponent);
    emptyFixture.detectChanges();
    await emptyFixture.whenStable();
    expect(api.getApplicationAnalyses).not.toHaveBeenCalled();
    expect(emptyFixture.nativeElement.textContent).toContain('Register a local system');
  });

  it('sorts history by parsed timestamps and uses ids as a deterministic tie-breaker', async () => {
    const offsetAnalyses: AnalysisSummaryResponse[] = [
      { ...analyses[0], id: 'analysis-z', startedAt: '2026-02-01T00:30:00+01:00' },
      { ...analyses[1], id: 'analysis-a', startedAt: '2026-02-01T00:00:00Z' },
      { ...analyses[1], id: 'analysis-b', startedAt: '2026-02-01T01:00:00+01:00' },
    ];
    api.getApplicationAnalyses.and.resolveTo(offsetAnalyses);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(component.analyses().map(analysis => analysis.id)).toEqual(['analysis-b', 'analysis-a', 'analysis-z']);
  });

  it('does not request history for malformed stored applications', async () => {
    localStorage.setItem('sgf.application', JSON.stringify({ id: 'app-1' }));
    const malformedFixture = TestBed.createComponent(DashboardComponent);
    malformedFixture.detectChanges();
    await malformedFixture.whenStable();
    expect(api.getApplicationAnalyses).not.toHaveBeenCalledWith('app-1');
    expect(malformedFixture.componentInstance.application()).toBeNull();
    expect(malformedFixture.nativeElement.textContent).toContain('Register a local system');
  });

  it('renders a recoverable error when history loading fails', async () => {
    api.getApplicationAnalyses.and.rejectWith(new Error('offline'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.analysis-card').length).toBe(0);
  });
});
