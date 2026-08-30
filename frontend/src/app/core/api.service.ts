import { Injectable } from '@angular/core';

export interface ProjectResponse { id: string; name: string; }
export interface ApplicationResponse { id: string; projectId: string; name: string; baseUrl: string; loginUrl: string; maxCrawlDepth?: number; excludedRoutes?: string[]; }
export interface AccessTestResult { reachable: boolean; authenticated: boolean; message?: string; }
export type AnalysisStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';
export interface AnalysisResponse { id: string; applicationId: string; status: AnalysisStatus; startedAt: string; completedAt: string | null; failureMessage: string | null; }
export interface AnalysisSummaryResponse { id: string; applicationId: string; status: AnalysisStatus; startedAt: string; completedAt: string | null; failureMessage: string | null; pageCount: number; }
export interface PageResponse { id: string; analysisId: string; url: string; title: string; }
export type ActionClassification = 'SAFE' | 'MUTATING' | 'UNKNOWN';
export interface ElementResponse { id: string; kind: string; selector: string; accessibleName: string | null; actionClassification: ActionClassification; }
export interface DocumentSectionResponse { id: string; position: number; sourcePageId: string; screenshotId: string | null; title: string; content: string; }
export interface DocumentResponse { id: string; title: string; applicationId: string; sourceAnalysisId: string; status: 'DRAFT'; sections: DocumentSectionResponse[]; }
export interface DocumentUpdatePayload { title: string; sections: Array<{ id: string; title: string; content: string }>; }
export interface ProjectInput { name: string; }
export interface ApplicationInput { name: string; baseUrl: string; loginUrl: string; username: string; password: string; maxCrawlDepth?: number; excludedRoutes?: string[]; }

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly apiUrl = '/api';
  createProject(input: ProjectInput): Promise<ProjectResponse> { return this.post('/projects', input); }
  createApplication(projectId: string, input: ApplicationInput): Promise<ApplicationResponse> { return this.post(`/projects/${projectId}/applications`, input); }
  updateApplication(applicationId: string, input: ApplicationInput): Promise<ApplicationResponse> { return this.request(`/applications/${applicationId}`, false, { method: 'PUT', body: JSON.stringify(input), headers: { 'Content-Type': 'application/json' } }); }
  testAccess(applicationId: string): Promise<AccessTestResult> { return this.post(`/applications/${applicationId}/test-access`, {}); }
  startAnalysis(applicationId: string): Promise<AnalysisResponse> { return this.post(`/applications/${applicationId}/analyses`, {}); }
  getApplicationAnalyses(applicationId: string): Promise<AnalysisSummaryResponse[]> { return this.request(`/applications/${applicationId}/analyses`); }
  getAnalysis(analysisId: string): Promise<AnalysisResponse> { return this.request(`/analyses/${analysisId}`); }
  getAnalysisPages(analysisId: string): Promise<PageResponse[]> { return this.request(`/analyses/${analysisId}/pages`); }
  getPageElements(pageId: string): Promise<ElementResponse[]> { return this.request(`/pages/${pageId}/elements`); }
  getPageScreenshot(pageId: string): Promise<Blob> { return this.request(`/pages/${pageId}/screenshot`, true); }
  generateDocument(analysisId: string): Promise<DocumentResponse> { return this.post(`/analyses/${analysisId}/document`, {}); }
  getDocument(documentId: string): Promise<DocumentResponse> { return this.request(`/documents/${documentId}`); }
  updateDocument(documentId: string, payload: DocumentUpdatePayload): Promise<DocumentResponse> { return this.request(`/documents/${documentId}`, false, { method: 'PUT', body: JSON.stringify(payload), headers: { 'Content-Type': 'application/json' } }); }
  private async post<T>(path: string, body: unknown): Promise<T> { return this.request(path, false, { method: 'POST', body: JSON.stringify(body), headers: { 'Content-Type': 'application/json' } }); }
  private async request<T>(path: string, blob = false, options: RequestInit = {}): Promise<T> { const response = await fetch(`${this.apiUrl}${path}`, options); if (!response.ok) throw new Error(`API request failed with status ${response.status}`); return (blob ? response.blob() : response.json()) as Promise<T>; }
}
