import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService, AnalysisResponse, DocumentResponse, DocumentSectionResponse, ElementResponse, PageResponse } from '../../core/api.service';
interface PageEvidence extends PageResponse { elements: ElementResponse[]; screenshotUrl: string | null; }
type SaveState = 'idle' | 'saving' | 'success' | 'error';

@Component({ selector: 'sgf-analysis', standalone: true, imports: [RouterLink], templateUrl: './analysis.component.html', styleUrl: './analysis.component.css' })
export class AnalysisComponent implements OnInit {
  private readonly api = inject(ApiService); private readonly route = inject(ActivatedRoute); private readonly destroyRef = inject(DestroyRef);
  readonly analysis = signal<AnalysisResponse | null>(null); readonly pages = signal<PageEvidence[]>([]); readonly document = signal<DocumentResponse | null>(null);
  readonly editableTitle = signal(''); readonly editableSections = signal<DocumentSectionResponse[]>([]);
  readonly state = signal<'loading' | 'ready' | 'error'>('loading'); readonly documentState = signal<'idle' | 'loading' | 'ready' | 'empty' | 'error'>('idle'); readonly saveState = signal<SaveState>('idle'); readonly errorMessage = signal('');
  ngOnInit(): void { const id = this.route.snapshot.paramMap.get('id'); if (!id) { this.fail('No analysis was selected.'); return; } void this.load(id); this.destroyRef.onDestroy(() => this.pages().forEach(p => p.screenshotUrl && URL.revokeObjectURL(p.screenshotUrl))); }
  private async load(id: string): Promise<void> { try { const analysis = await this.api.getAnalysis(id); this.analysis.set(analysis); const pages = await this.api.getAnalysisPages(id); this.pages.set(await Promise.all(pages.map(p => this.loadPage(p)))); this.state.set('ready'); } catch { this.fail('The analysis could not be loaded. Check that the API is running and try again.'); } }
  private async loadPage(page: PageResponse): Promise<PageEvidence> { const [elements, screenshot] = await Promise.all([this.api.getPageElements(page.id).catch(() => [] as ElementResponse[]), this.api.getPageScreenshot(page.id).catch(() => null)]); return { ...page, elements, screenshotUrl: screenshot ? URL.createObjectURL(screenshot) : null }; }
  async generateDocument(): Promise<void> { const id = this.analysis()?.id; if (!id) return; this.documentState.set('loading'); try { const generated = await this.api.generateDocument(id); this.setEditableDocument(generated); this.documentState.set(generated.sections.length ? 'ready' : 'empty'); } catch { this.documentState.set('error'); } }
  editTitle(title: string): void { this.ensureEditable(); this.editableTitle.set(title); }
  editSectionTitle(id: string, title: string): void { this.updateSection(id, section => ({ ...section, title })); }
  editSectionContent(id: string, content: string): void { this.updateSection(id, section => ({ ...section, content })); }
  moveSection(id: string, offset: number): void { this.ensureEditable(); const sections = [...this.editableSections()]; const index = sections.findIndex(section => section.id === id); const target = index + offset; if (index < 0 || target < 0 || target >= sections.length) return; [sections[index], sections[target]] = [sections[target], sections[index]]; this.editableSections.set(sections); }
  async saveDocument(): Promise<void> { const current = this.document(); if (!current) return; this.ensureEditable(); this.saveState.set('saving'); try { const saved = await this.api.updateDocument(current.id, { title: this.editableTitle(), sections: this.editableSections().map(section => ({ id: section.id, title: section.title, content: section.content })) }); this.setEditableDocument(saved); this.saveState.set('success'); } catch { this.saveState.set('error'); } }
  private updateSection(id: string, update: (section: DocumentSectionResponse) => DocumentSectionResponse): void { this.ensureEditable(); this.editableSections.set(this.editableSections().map(section => section.id === id ? update(section) : section)); }
  private ensureEditable(): void { if (!this.document() || this.editableSections().length) return; this.setEditableDocument(this.document()!); }
  private setEditableDocument(document: DocumentResponse): void { this.document.set(document); this.editableTitle.set(document.title); this.editableSections.set(document.sections.map((section, index) => ({ ...section, position: index }))); }
  private fail(message: string): void { this.state.set('error'); this.errorMessage.set(message); }
}
