import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService, AnalysisResponse, DocumentResponse, DocumentSectionResponse, ElementResponse, FunctionalModule, PageResponse, LocalizationService, DocumentLanguage, DocumentType } from '../../core/api.service';

interface PageEvidence extends PageResponse { elements: ElementResponse[]; screenshotUrl: string | null; }
interface ManualReviewGroup { page: PageEvidence; elements: ElementResponse[]; }
type SaveState = 'idle' | 'saving' | 'success' | 'error';

@Component({ selector: 'sgf-analysis', standalone: true, imports: [RouterLink], templateUrl: './analysis.component.html', styleUrl: './analysis.component.css' })
export class AnalysisComponent implements OnInit {
  private readonly api = inject(ApiService); private readonly route = inject(ActivatedRoute); readonly localization = inject(LocalizationService); readonly t = (key: string): string => this.localization.t(key); private readonly destroyRef = inject(DestroyRef);
  readonly analysis = signal<AnalysisResponse | null>(null); readonly pages = signal<PageEvidence[]>([]); readonly modules = signal<FunctionalModule[]>([]); readonly document = signal<DocumentResponse | null>(null);
  readonly editableTitle = signal(''); readonly documentLanguage = signal<DocumentLanguage>(this.readDocumentLanguage()); readonly documentType = signal<DocumentType>('user_manual'); readonly editableSections = signal<DocumentSectionResponse[]>([]);
  readonly searchQuery = signal(''); readonly selectedModule = signal('all');
  readonly filteredSections = computed(() => { const query = this.normalizedSearch(); return this.editableSections().filter(section => !query || this.searchText(section.title, section.content, section.sourcePageId).includes(query)); });
  readonly visibleSections = computed(() => this.filteredSections().filter(section => !section.hidden));
  readonly hiddenSections = computed(() => this.editableSections().filter(section => section.hidden));
  readonly unknownReviewGroups = computed<ManualReviewGroup[]>(() => this.pages().map(page => ({ page, elements: page.elements.filter(element => element.actionClassification === 'UNKNOWN') })).filter(group => group.elements.length));
  readonly unknownElementCount = computed(() => this.unknownReviewGroups().reduce((count, group) => count + group.elements.length, 0));
  readonly approvedUnknownElementCount = computed(() => this.unknownReviewGroups().reduce((count, group) => count + group.elements.filter(element => element.manualInclusionApproved).length, 0));
  readonly filteredModules = computed(() => { const query = this.normalizedSearch(); const selected = this.selectedModule(); return this.modules().map(module => { const moduleMatches = !query || this.searchText(module.name, module.key).includes(query); if (selected !== 'all' && module.key !== selected) return null; const pages = module.pages.filter(page => moduleMatches || this.pageMatches(page, query)); return pages.length ? { ...module, pages } : null; }).filter((module): module is FunctionalModule => module !== null); });
  readonly state = signal<'loading' | 'ready' | 'error'>('loading'); readonly moduleState = signal<'loading' | 'ready' | 'error'>('loading'); readonly documentState = signal<'idle' | 'loading' | 'ready' | 'empty' | 'error'>('idle'); readonly saveState = signal<SaveState>('idle'); readonly manualInclusionState = signal<'idle' | 'saving' | 'error'>('idle'); readonly manualInclusionMessage = signal(''); readonly errorMessage = signal('');

  ngOnInit(): void { const id = this.route.snapshot.paramMap.get('id'); if (!id) { this.fail(this.t('no-analysis-selected')); return; } void this.load(id); this.destroyRef.onDestroy(() => this.pages().forEach(p => p.screenshotUrl && URL.revokeObjectURL(p.screenshotUrl))); }
  private async load(id: string): Promise<void> { try { const analysis = await this.api.getAnalysis(id); this.analysis.set(analysis); this.moduleState.set('loading'); const [pages, moduleResult] = await Promise.all([this.api.getAnalysisPages(id), this.api.getAnalysisModules(id).then(modules => ({ modules, failed: false })).catch(() => ({ modules: [], failed: true }))]); const pageEvidence = await Promise.all(pages.map(p => this.loadPage(p))); this.pages.set(pageEvidence); this.modules.set(moduleResult.failed ? (pages.length ? [{ key: 'unassigned', name: this.t('unassigned-pages'), pages }] : []) : moduleResult.modules); this.moduleState.set(moduleResult.failed ? 'error' : 'ready'); this.state.set('ready'); } catch { this.fail(this.t('analysis-load-error')); } }
  pageEvidence(id: string): PageEvidence | undefined { return this.pages().find(page => page.id === id); }
  pageLabel(page: PageResponse): string { const title = page.title?.trim(); if (!title) return this.routeLabel(page.url); return this.pages().filter(candidate => candidate.title?.trim() === title).length > 1 ? `${title} · ${this.routeLabel(page.url)}` : title; }
  elementLabel(element: ElementResponse): string { return element.accessibleName?.trim() || this.t('unnamed-element'); }
  routeLabel(url: string): string { try { return new URL(url).pathname || '/'; } catch { return url || '/'; } }
  sectionAnchor(id: string): string { return `manual-section-${this.anchorPart(id)}`; }
  pageAnchor(id: string): string { return `discovered-page-${this.anchorPart(id)}`; }
  moduleAnchor(key: string): string { return `module-${this.anchorPart(key)}`; }
  private normalizedSearch(): string { return this.searchQuery().trim().toLocaleLowerCase(); }
  private searchText(...values: Array<string | null | undefined>): string { return values.filter(Boolean).join(' ').toLocaleLowerCase(); }
  private pageMatches(page: PageResponse, query: string): boolean { if (!query) return true; const evidence = this.pageEvidence(page.id); const sections = this.editableSections().filter(section => section.sourcePageId === page.id); return this.searchText(page.title, page.url, ...sections.flatMap(section => [section.title, section.content]), ...(evidence?.elements || []).flatMap(element => [element.selector, element.accessibleName, element.kind])).includes(query); }
  private anchorPart(value: string): string { return value.toLocaleLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'item'; }
  setSearchQuery(query: string): void { this.searchQuery.set(query); }
  setSelectedModule(module: string): void { this.selectedModule.set(module); }
  private async loadPage(page: PageResponse): Promise<PageEvidence> { const [elements, screenshot] = await Promise.all([this.api.getPageElements(page.id).catch(() => [] as ElementResponse[]), this.api.getPageScreenshot(page.id).catch(() => null)]); return { ...page, elements, screenshotUrl: screenshot ? URL.createObjectURL(screenshot) : null }; }
  async generateDocument(): Promise<void> { const id = this.analysis()?.id; if (!id) return; const current = this.document(); const language = this.documentLanguage(); const type = this.documentType(); if (current && (current.language !== language || current.type !== type) && !window.confirm(this.documentReplacementWarning())) return; this.documentState.set('loading'); try { const generated = await this.api.generateDocument(id, { language, type }); this.setEditableDocument(generated); this.documentState.set(generated.sections.length ? 'ready' : 'empty'); } catch { this.documentState.set('error'); } }
  async setManualInclusionApproval(elementId: string, approved: boolean): Promise<void> { this.manualInclusionState.set('saving'); this.manualInclusionMessage.set(''); try { const updated = await this.api.updateManualInclusion(elementId, approved); this.pages.update(pages => pages.map(page => ({ ...page, elements: page.elements.map(element => element.id === elementId ? updated : element) }))); if (this.document()) { this.document.set(null); this.editableTitle.set(''); this.editableSections.set([]); this.documentState.set('idle'); this.saveState.set('idle'); this.manualInclusionMessage.set(this.t('draft-invalidated')); } else this.manualInclusionMessage.set(this.t('manual-inclusion-updated')); this.manualInclusionState.set('idle'); } catch { this.manualInclusionState.set('error'); this.manualInclusionMessage.set(this.t('manual-inclusion-error')); } }
  editTitle(title: string): void { this.ensureEditable(); this.editableTitle.set(title); }
  editSectionTitle(id: string, title: string): void { this.updateSection(id, section => ({ ...section, title })); }
  editSectionContent(id: string, content: string): void { this.updateSection(id, section => ({ ...section, content })); }
  moveSection(id: string, offset: number): void { this.ensureEditable(); const sections = [...this.editableSections()]; const index = sections.findIndex(section => section.id === id); const target = index + offset; if (index < 0 || target < 0 || target >= sections.length) return; [sections[index], sections[target]] = [sections[target], sections[index]]; this.editableSections.set(sections); }
  async saveDocument(): Promise<void> { const current = this.document(); if (!current) return; this.ensureEditable(); this.saveState.set('saving'); try { const saved = await this.api.updateDocument(current.id, { title: this.editableTitle(), sections: this.editableSections().map(section => ({ id: section.id, title: section.title, content: section.content, hidden: section.hidden })) }); this.setEditableDocument(saved); this.saveState.set('success'); } catch { this.saveState.set('error'); } }
  setSectionHidden(id: string, hidden: boolean): void { this.updateSection(id, section => ({ ...section, hidden })); }
  private updateSection(id: string, update: (section: DocumentSectionResponse) => DocumentSectionResponse): void { this.ensureEditable(); this.editableSections.set(this.editableSections().map(section => section.id === id ? update(section) : section)); }
  private ensureEditable(): void { if (!this.document() || this.editableSections().length) return; this.setEditableDocument(this.document()!); }
  setDocumentLanguage(language: DocumentLanguage): void { this.documentLanguage.set(language); localStorage.setItem('sgf.document-language', language); }
  setDocumentType(type: DocumentType): void { this.documentType.set(type); }
  private readDocumentLanguage(): DocumentLanguage { return localStorage.getItem('sgf.document-language') === 'es' ? 'es' : 'en'; }
  private setEditableDocument(document: DocumentResponse): void { this.document.set(document); this.documentLanguage.set(document.language); this.documentType.set(document.type); this.editableTitle.set(document.title); this.editableSections.set(document.sections.map((section, index) => ({ ...section, position: index }))); }
  private documentReplacementWarning(): string { return this.localization.language() === 'es' ? 'Este borrador editable existente se eliminará y se reemplazará. ¿Querés continuar?' : 'The existing editable draft will be destroyed and replaced. Do you want to continue?'; }
  private fail(message: string): void { this.state.set('error'); this.errorMessage.set(message); }
}
