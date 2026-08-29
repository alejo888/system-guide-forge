import { Routes } from '@angular/router';
import { DashboardComponent } from './features/dashboard/dashboard.component';
import { RegistrationComponent } from './features/registration/registration.component';
import { AnalysisComponent } from './features/analysis/analysis.component';

export const routes: Routes = [
  { path: '', component: DashboardComponent },
  { path: 'register', component: RegistrationComponent },
  { path: 'edit', component: RegistrationComponent },
  { path: 'analysis/:id', component: AnalysisComponent },
  { path: '**', redirectTo: '' }
];
