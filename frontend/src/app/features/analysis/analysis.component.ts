import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService, AnalysisResponse, DocumentResponse, ElementResponse, PageResponse } from '../../core/api.service';
interface PageEvidence extends PageResponse { elements: ElementResponse[]; screenshotUrl: string | null; }
@Component({ selector: 'sgf-analysis', standalone: true, imports: [RouterLink], templateUrl: './analysis.component.html', styleUrl: './analysis.component.css' })
export class AnalysisComponent implements OnInit {
  private readonly api = inject(ApiService); private readonly route = inject(ActivatedRoute); private readonly destroyRef = inject(DestroyRef);
  readonly analysis = signal<AnalysisResponse | null>(null); readonly pages = signal<PageEvidence[]>([]); readonly document = signal<DocumentResponse | null>(null);
  readonly state = signal<'loading' | 'ready' | 'error'>('loading'); readonly documentState = signal<'idle' | 'loading' | 'ready' | 'empty' | 'error'>('idle'); readonly errorMessage = signal('');
  ngOnInit(): void { const id = this.route.snapshot.paramMap.get('id'); if (!id) { this.fail('No analysis was selected.'); return; } void this.load(id); this.destroyRef.onDestroy(() => this.pages().forEach(p => p.screenshotUrl && URL.revokeObjectURL(p.screenshotUrl))); }
  private async load(id: string): Promise<void> { try { const analysis = await this.api.getAnalysis(id); this.analysis.set(analysis); const pages = await this.api.getAnalysisPages(id); this.pages.set(await Promise.all(pages.map(p => this.loadPage(p)))); this.state.set('ready'); } catch { this.fail('The analysis could not be loaded. Check that the API is running and try again.'); } }
  private async loadPage(page: PageResponse): Promise<PageEvidence> { const [elements, screenshot] = await Promise.all([this.api.getPageElements(page.id).catch(() => [] as ElementResponse[]), this.api.getPageScreenshot(page.id).catch(() => null)]); return { ...page, elements, screenshotUrl: screenshot ? URL.createObjectURL(screenshot) : null }; }
  async generateDocument(): Promise<void> { const id = this.analysis()?.id; if (!id) return; this.documentState.set('loading'); try { const document = await this.api.generateDocument(id); this.document.set(document); this.documentState.set(document.sections.length ? 'ready' : 'empty'); } catch { this.documentState.set('error'); } }
  private fail(message: string): void { this.state.set('error'); this.errorMessage.set(message); }
}
