import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiError, ApiService, AnalysisResponse, ApprovedOpener, DocumentResponse, DocumentSectionResponse, ElementResponse, FunctionalModule, PageResponse, LocalizationService, DocumentLanguage, DocumentType } from '../../core/api.service';

interface PageEvidence extends PageResponse { elements: ElementResponse[]; screenshotUrl: string | null; }
interface ManualReviewGroup { page: PageEvidence; elements: ElementResponse[]; }
type SaveState = 'idle' | 'saving' | 'success' | 'error';
type RetryState = 'idle' | 'starting' | 'error';
type DocxExportState = 'idle' | 'exporting' | 'error';
type DraftLookupState = 'idle' | 'loading';
interface OpenerFeedback { elementId: string; kind: 'success' | 'error'; message: string; }
/** Mirrors the backend RF23 id segment: purely numeric or a UUID. */
const ID_SEGMENT = /^(?:[0-9]+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$/;

@Component({ selector: 'sgf-analysis', standalone: true, imports: [RouterLink], templateUrl: './analysis.component.html', styleUrl: './analysis.component.css' })
export class AnalysisComponent implements OnInit {
  private readonly api = inject(ApiService); private readonly route = inject(ActivatedRoute); private readonly router = inject(Router); readonly localization = inject(LocalizationService); readonly t = (key: string): string => this.localization.t(key); private readonly destroyRef = inject(DestroyRef);
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
  readonly state = signal<'loading' | 'ready' | 'error'>('loading'); readonly moduleState = signal<'loading' | 'ready' | 'error'>('loading'); readonly documentState = signal<'idle' | 'loading' | 'ready' | 'empty' | 'error'>('idle'); readonly saveState = signal<SaveState>('idle'); readonly manualInclusionState = signal<'idle' | 'saving' | 'error'>('idle'); readonly manualInclusionMessage = signal(''); readonly retryState = signal<RetryState>('idle'); readonly retryErrorMessage = signal(''); readonly errorMessage = signal(''); readonly docxExportState = signal<DocxExportState>('idle'); readonly draftLookupState = signal<DraftLookupState>('idle');
  /** RF24 openers already approved for this analysis' application; empty when they could not be loaded. */
  readonly approvedOpeners = signal<ApprovedOpener[]>([]); readonly openerPendingIds = signal<ReadonlySet<string>>(new Set()); readonly openerFeedback = signal<ReadonlyMap<string, OpenerFeedback>>(new Map());
  private draftGenerationStarted = false;
  /** Every opener-list request (load or approval) takes the next number, so a load that resolves after a newer approval was applied is discarded. */
  private openerRequestSequence = 0; private openerAppliedSequence = 0;

  ngOnInit(): void { const id = this.route.snapshot.paramMap.get('id'); if (!id) { this.fail(this.t('no-analysis-selected')); return; } void this.load(id); this.destroyRef.onDestroy(() => this.pages().forEach(p => p.screenshotUrl && URL.revokeObjectURL(p.screenshotUrl))); }
  private async load(id: string): Promise<void> { try { const analysis = await this.api.getAnalysis(id); this.analysis.set(analysis); void this.loadApprovedOpeners(analysis.applicationId); this.moduleState.set('loading'); const [pages, moduleResult] = await Promise.all([this.api.getAnalysisPages(id), this.api.getAnalysisModules(id).then(modules => ({ modules, failed: false })).catch(() => ({ modules: [], failed: true }))]); const pageEvidence = await Promise.all(pages.map(p => this.loadPage(p))); this.pages.set(pageEvidence); this.modules.set(moduleResult.failed ? (pages.length ? [{ key: 'unassigned', name: this.t('unassigned-pages'), pages }] : []) : moduleResult.modules); this.moduleState.set(moduleResult.failed ? 'error' : 'ready'); this.state.set('ready'); await this.loadExistingDraft(id); } catch { this.fail(this.t('analysis-load-error')); } }
  /** Auto-loads the analysis' single existing draft (if any) so it does not need to be regenerated on reload.
   * A 404 (no draft yet) and any other lookup failure both stay non-blocking: the state remains 'idle' and
   * manual generation remains available. If the user already triggered generation before this resolves, its
   * result is discarded so it never clobbers the freshly generated draft. */
  private async loadExistingDraft(analysisId: string): Promise<void> {
    if (this.draftGenerationStarted) return;
    this.draftLookupState.set('loading');
    try {
      const draft = await this.api.getAnalysisDocument(analysisId);
      if (this.draftGenerationStarted) return;
      this.setGeneratedDocument(draft);
    } catch {
      // 404 (no existing draft) and any other lookup failure both leave the state idle without an error.
    } finally {
      this.draftLookupState.set('idle');
    }
  }
  /** Loads the application's approved openers once; any failure leaves the list empty and approving still available. */
  private async loadApprovedOpeners(applicationId: string): Promise<void> {
    const sequence = ++this.openerRequestSequence;
    try { this.applyApprovedOpeners(sequence, (await this.api.getApplication(applicationId)).approvedOpeners, false); } catch { /* Unknown openers: every named button keeps its approve action. */ }
  }
  /** A load replaces the list unless a newer approval was already applied. An approval response is merged instead:
   * approvals only add openers, and concurrent approvals may be answered by the server in any order, so a response that
   * lacks another just-approved opener must not drop it. */
  private applyApprovedOpeners(sequence: number, openers: ApprovedOpener[] | null | undefined, merge: boolean): void {
    if (!merge && sequence < this.openerAppliedSequence) return;
    const received = openers ?? [];
    const key = (opener: ApprovedOpener): string => `${this.routeTemplate(opener.routePattern)}\n${this.openerNameKey(opener.accessibleName)}`;
    const receivedKeys = new Set(received.map(key));
    this.openerAppliedSequence = Math.max(this.openerAppliedSequence, sequence);
    this.approvedOpeners.set(merge ? [...received, ...this.approvedOpeners().filter(opener => !receivedKeys.has(key(opener)))] : received);
  }
  isOpenerPending(element: ElementResponse): boolean { return this.openerPendingIds().has(element.id); }
  openerFeedbackFor(element: ElementResponse): OpenerFeedback | undefined { return this.openerFeedback().get(element.id); }
  private setOpenerPending(elementId: string, pending: boolean): void { this.openerPendingIds.update(ids => { const next = new Set(ids); if (pending) next.add(elementId); else next.delete(elementId); return next; }); }
  private setOpenerFeedback(elementId: string, feedback: OpenerFeedback | null): void { this.openerFeedback.update(current => { const next = new Map(current); if (feedback) next.set(elementId, feedback); else next.delete(elementId); return next; }); }
  /** Only named buttons can open a form; links, fields, unnamed controls, and tabs never become openers. */
  canApproveOpener(element: ElementResponse): boolean { return element.kind === 'button' && element.controlType !== 'tab' && !!element.accessibleName?.trim(); }
  /** Mirrors the backend ApprovedOpenerMatcher: same route template and same visible name ignoring case and spacing. */
  isApprovedOpener(page: PageResponse, element: ElementResponse): boolean {
    const route = this.routeTemplate(this.routeLabel(page.url)); const name = this.openerNameKey(element.accessibleName);
    return this.approvedOpeners().some(opener => this.routeTemplate(opener.routePattern) === route && this.openerNameKey(opener.accessibleName) === name);
  }
  approveOpenerLabel(element: ElementResponse): string { return `${this.t('approve-opener')}: ${this.elementLabel(element)}`; }
  /** RF24: approves the control for future analyses only; the current evidence, classification, and draft stay as they are. */
  async approveOpener(page: PageResponse, element: ElementResponse): Promise<void> {
    const applicationId = this.analysis()?.applicationId;
    if (!applicationId || !element.accessibleName || this.isOpenerPending(element)) return;
    this.setOpenerPending(element.id, true); this.setOpenerFeedback(element.id, null);
    const sequence = ++this.openerRequestSequence;
    try {
      const application = await this.api.approveOpener(applicationId, { routePattern: new URL(page.url).pathname, accessibleName: element.accessibleName });
      this.applyApprovedOpeners(sequence, application.approvedOpeners, true);
      this.setOpenerFeedback(element.id, { elementId: element.id, kind: 'success', message: this.t('opener-approved-next-analysis') });
    } catch (error) {
      const message = error instanceof ApiError && error.status === 400 && error.message ? error.message : this.t('opener-approve-error');
      this.setOpenerFeedback(element.id, { elementId: element.id, kind: 'error', message });
    } finally {
      this.setOpenerPending(element.id, false);
    }
  }
  /** The RF23 route template of a path: numeric or UUID segments become {id}; a trailing slash is dropped. */
  private routeTemplate(path: string): string { const template = path.split('/').map(segment => ID_SEGMENT.test(segment) ? '{id}' : segment).join('/'); return template.length > 1 && template.endsWith('/') ? template.slice(0, -1) : template; }
  /** NFC, Java \s whitespace collapsed and trimmed, lower case: the backend's ApprovedOpenerMatcher.nameKey. */
  private openerNameKey(name: string | null): string { return (name ?? '').normalize('NFC').replace(/[ \t\n\x0B\f\r]+/g, ' ').trim().toLowerCase(); }
  /** The completed-status sentence with the real number of pages that were analyzed. */
  completedText(): string { const count = this.pages().length; return count === 1 ? this.t('progress-completed-one') : this.t('progress-completed-many').replace('{n}', String(count)); }
  pageEvidence(id: string): PageEvidence | undefined { return this.pages().find(page => page.id === id); }
  pageLabel(page: PageResponse): string { const base = this.baseLabel(page); if (!base) return this.routeLabel(page.url); return this.pages().filter(candidate => this.baseLabel(candidate) === base).length > 1 ? `${base} · ${this.routeLabel(page.url)}` : base; }
  private baseLabel(page: PageResponse): string { return page.heading?.trim() || page.title?.trim() || ''; }
  elementLabel(element: ElementResponse): string { return element.accessibleName?.trim() || this.t('unnamed-element'); }
  isLoginPage(page: PageResponse): boolean { return page.kind === 'LOGIN'; }
  routeLabel(url: string): string { try { return new URL(url).pathname || '/'; } catch { return url || '/'; } }
  sectionAnchor(id: string): string { return `manual-section-${this.anchorPart(id)}`; }
  pageAnchor(id: string): string { return `discovered-page-${this.anchorPart(id)}`; }
  moduleAnchor(key: string): string { return `module-${this.anchorPart(key)}`; }
  private normalizedSearch(): string { return this.searchQuery().trim().toLocaleLowerCase(); }
  private searchText(...values: Array<string | null | undefined>): string { return values.filter(Boolean).join(' ').toLocaleLowerCase(); }
  private pageMatches(page: PageResponse, query: string): boolean { if (!query) return true; const evidence = this.pageEvidence(page.id); const sections = this.editableSections().filter(section => section.sourcePageId === page.id); return this.searchText(page.title, page.heading, page.url, ...sections.flatMap(section => [section.title, section.content]), ...(evidence?.elements || []).flatMap(element => [element.selector, element.accessibleName, element.kind])).includes(query); }
  private anchorPart(value: string): string { return value.toLocaleLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'item'; }
  setSearchQuery(query: string): void { this.searchQuery.set(query); }
  setSelectedModule(module: string): void { this.selectedModule.set(module); }
  private async loadPage(page: PageResponse): Promise<PageEvidence> { const [elements, screenshot] = await Promise.all([this.api.getPageElements(page.id).catch(() => [] as ElementResponse[]), this.api.getPageScreenshot(page.id).catch(() => null)]); return { ...page, elements, screenshotUrl: screenshot ? URL.createObjectURL(screenshot) : null }; }
  safeFailureDetail(message: string | null): string | null {
    if (!message) return null;
    const known = ['Browser operation timeout', 'Authentication failed', 'Unsafe navigation rejected', 'Sanitized screenshot unavailable', 'Browser navigation failed', 'Browser unavailable', 'Browser operation failed'];
    const prefix = 'Screen analysis unavailable or failed: ';
    const detail = message.startsWith(prefix) ? message.slice(prefix.length) : message;
    return known.find(label => detail.toLowerCase().startsWith(label.toLowerCase())) ?? null;
  }
  async retryAnalysis(): Promise<void> {
    const failedAnalysis = this.analysis();
    if (!failedAnalysis || failedAnalysis.status !== 'FAILED' || this.retryState() === 'starting') return;
    if (!window.confirm(this.t('retry-analysis-confirmation'))) return;
    this.retryState.set('starting');
    this.retryErrorMessage.set('');
    try {
      const analysis = await this.api.startAnalysis(failedAnalysis.applicationId);
      await this.router.navigate(['/analysis', analysis.id]);
      this.retryState.set('idle');
    } catch {
      this.retryState.set('error');
      this.retryErrorMessage.set(this.t('retry-analysis-error'));
    }
  }
  async generateDocument(): Promise<void> {
    const id = this.analysis()?.id;
    if (!id) return;
    this.draftGenerationStarted = true;
    this.draftLookupState.set('idle');
    const current = this.document();
    const language = this.documentLanguage();
    const type = this.documentType();
    const replacementConfirmed = !!current && (current.language !== language || current.type !== type);
    if (replacementConfirmed && !window.confirm(this.documentReplacementWarning())) return;
    const priorDocumentState = this.documentState();
    this.documentState.set('loading');
    try {
      const generated = await this.api.generateDocument(id, { language, type, confirmReplacement: replacementConfirmed });
      this.setGeneratedDocument(generated);
    } catch (error) {
      if (!replacementConfirmed && this.isReplacementConfirmationRequired(error)) {
        if (!window.confirm(this.documentReplacementWarning())) {
          this.documentState.set(priorDocumentState);
          return;
        }
        try {
          const generated = await this.api.generateDocument(id, { language, type, confirmReplacement: true });
          this.setGeneratedDocument(generated);
        } catch {
          this.documentState.set('error');
        }
        return;
      }
      this.documentState.set('error');
    }
  }
  async setManualInclusionApproval(elementId: string, approved: boolean): Promise<void> { this.manualInclusionState.set('saving'); this.manualInclusionMessage.set(''); try { const updated = await this.api.updateManualInclusion(elementId, approved); this.pages.update(pages => pages.map(page => ({ ...page, elements: page.elements.map(element => element.id === elementId ? updated : element) }))); if (this.document()) { this.document.set(null); this.editableTitle.set(''); this.editableSections.set([]); this.documentState.set('idle'); this.saveState.set('idle'); this.manualInclusionMessage.set(this.t('draft-invalidated')); } else this.manualInclusionMessage.set(this.t('manual-inclusion-updated')); this.manualInclusionState.set('idle'); } catch { this.manualInclusionState.set('error'); this.manualInclusionMessage.set(this.t('manual-inclusion-error')); } }
  editTitle(title: string): void { this.ensureEditable(); this.editableTitle.set(title); }
  editSectionTitle(id: string, title: string): void { this.updateSection(id, section => ({ ...section, title })); }
  editSectionContent(id: string, content: string): void { this.updateSection(id, section => ({ ...section, content })); }
  moveSection(id: string, offset: number): void { this.ensureEditable(); const sections = [...this.editableSections()]; const index = sections.findIndex(section => section.id === id); const target = index + offset; if (index < 0 || target < 0 || target >= sections.length) return; [sections[index], sections[target]] = [sections[target], sections[index]]; this.editableSections.set(sections); }
  async saveDocument(): Promise<void> { const current = this.document(); if (!current) return; this.ensureEditable(); this.saveState.set('saving'); try { const saved = await this.api.updateDocument(current.id, { title: this.editableTitle(), sections: this.editableSections().map(section => ({ id: section.id, title: section.title, content: section.content, hidden: section.hidden })) }); this.setEditableDocument(saved); this.saveState.set('success'); } catch { this.saveState.set('error'); } }
  async downloadDocx(): Promise<void> {
    const current = this.document();
    if (!current || this.docxExportState() === 'exporting') return;
    this.docxExportState.set('exporting');
    let url: string | null = null;
    try {
      const blob = await this.api.exportDocument(current.id);
      url = URL.createObjectURL(blob);
      const link = window.document.createElement('a');
      link.href = url;
      link.download = this.docxFilename(current.title);
      window.document.body.appendChild(link);
      link.click();
      link.remove();
      this.docxExportState.set('idle');
    } catch {
      this.docxExportState.set('error');
    } finally {
      // Deferred so the browser can begin the download before the object URL is released;
      // revoking it synchronously right after click() can cancel the in-flight download.
      if (url) { const revokeUrl = url; setTimeout(() => URL.revokeObjectURL(revokeUrl), 0); }
    }
  }
  setSectionHidden(id: string, hidden: boolean): void { this.updateSection(id, section => ({ ...section, hidden })); }
  private updateSection(id: string, update: (section: DocumentSectionResponse) => DocumentSectionResponse): void { this.ensureEditable(); this.editableSections.set(this.editableSections().map(section => section.id === id ? update(section) : section)); }
  private ensureEditable(): void { if (!this.document() || this.editableSections().length) return; this.setEditableDocument(this.document()!); }
  setDocumentLanguage(language: DocumentLanguage): void { this.documentLanguage.set(language); localStorage.setItem('sgf.document-language', language); }
  setDocumentType(type: DocumentType): void { this.documentType.set(type); }
  private readDocumentLanguage(): DocumentLanguage { return localStorage.getItem('sgf.document-language') === 'es' ? 'es' : 'en'; }
  private docxFilename(title: string | null | undefined): string { const safeTitle = (title ?? '').replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, ''); return `${safeTitle || 'manual'}.docx`; }
  private setEditableDocument(document: DocumentResponse): void { this.document.set(document); this.documentLanguage.set(document.language); this.documentType.set(document.type); this.editableTitle.set(document.title); this.editableSections.set(document.sections.map((section, index) => ({ ...section, position: index }))); }
  private setGeneratedDocument(document: DocumentResponse): void { this.setEditableDocument(document); this.documentState.set(document.sections.length ? 'ready' : 'empty'); }
  private isReplacementConfirmationRequired(error: unknown): boolean { return error instanceof ApiError && error.status === 409 && error.code === 'DRAFT_REPLACEMENT_CONFIRMATION_REQUIRED'; }
  private documentReplacementWarning(): string { return this.localization.language() === 'es' ? 'Este borrador editable existente se eliminará y se reemplazará. ¿Querés continuar?' : 'The existing editable draft will be destroyed and replaced. Do you want to continue?'; }
  private fail(message: string): void { this.state.set('error'); this.errorMessage.set(message); }
}
