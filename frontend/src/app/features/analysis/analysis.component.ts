import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService, AnalysisResponse, ElementResponse, PageResponse } from '../../core/api.service';

interface PageEvidence extends PageResponse { elements: ElementResponse[]; screenshotUrl: string | null; }

@Component({
  selector: 'sgf-analysis', standalone: true, imports: [RouterLink],
  templateUrl: './analysis.component.html', styleUrl: './analysis.component.css'
})
export class AnalysisComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  readonly analysis = signal<AnalysisResponse | null>(null);
  readonly pages = signal<PageEvidence[]>([]);
  readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  readonly errorMessage = signal('');

  ngOnInit(): void {
    const analysisId = this.route.snapshot.paramMap.get('id');
    if (!analysisId) { this.fail('No analysis was selected.'); return; }
    void this.load(analysisId);
    this.destroyRef.onDestroy(() => this.pages().forEach(page => page.screenshotUrl && URL.revokeObjectURL(page.screenshotUrl)));
  }

  private async load(analysisId: string): Promise<void> {
    try {
      const analysis = await this.api.getAnalysis(analysisId);
      this.analysis.set(analysis);
      const pages = await this.api.getAnalysisPages(analysis.id);
      this.pages.set(await Promise.all(pages.map(page => this.loadPage(page))));
      this.state.set('ready');
    } catch { this.fail('The analysis could not be loaded. Check that the API is running and try again.'); }
  }

  private async loadPage(page: PageResponse): Promise<PageEvidence> {
    const [elements, screenshot] = await Promise.all([
      this.api.getPageElements(page.id).catch(() => [] as ElementResponse[]),
      this.api.getPageScreenshot(page.id).catch(() => null)
    ]);
    return { ...page, elements, screenshotUrl: screenshot ? URL.createObjectURL(screenshot) : null };
  }

  private fail(message: string): void { this.state.set('error'); this.errorMessage.set(message); }
}
