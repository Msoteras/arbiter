import { Routes } from '@angular/router';

import { guestGuard } from './core/auth/guest.guard';
import { onboardingGuard, onboardingPendingGuard } from './core/auth/onboarding.guard';
import { roleGuard } from './core/auth/role.guard';
import { rememberedReportsTab } from './features/admin/reports/reports-tab-memory';

export const routes: Routes = [
  { path: '', redirectTo: 'login', pathMatch: 'full' },

  {
    path: 'login',
    canActivate: [guestGuard],
    loadComponent: () =>
      import('./features/auth/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'activate-account',
    data: { mode: 'activate' },
    loadComponent: () =>
      import('./features/auth/activate-account/activate-account.component').then(
        (m) => m.ActivateAccountComponent,
      ),
  },
  {
    path: 'forgot-password',
    loadComponent: () =>
      import('./features/auth/forgot-password/forgot-password.component').then(
        (m) => m.ForgotPasswordComponent,
      ),
  },
  {
    path: 'reset-password',
    data: { mode: 'reset' },
    loadComponent: () =>
      import('./features/auth/activate-account/activate-account.component').then(
        (m) => m.ActivateAccountComponent,
      ),
  },

  // ----- Home screens (one per role) -----
  {
    path: 'home',
    canActivate: [roleGuard],
    data: { roles: ['ANALISTA_SINIESTROS'] },
    loadComponent: () =>
      import('./features/home/analyst-home/analyst-home.component').then(
        (m) => m.AnalystHomeComponent,
      ),
  },
  {
    path: 'insurer/home',
    canActivate: [roleGuard],
    data: { roles: ['REFERENTE_ASEGURADORA'] },
    loadComponent: () =>
      import('./features/home/referent-home/referent-home.component').then(
        (m) => m.ReferentHomeComponent,
      ),
  },
  {
    path: 'portal/home',
    canActivate: [roleGuard, onboardingGuard],
    data: { roles: ['ASEGURADO'] },
    loadComponent: () =>
      import('./features/home/insured-home/insured-home.component').then(
        (m) => m.InsuredHomeComponent,
      ),
  },

  // ----- Analyst portal (the referente also sees it, read-only: no assigning or deciding) -----
  {
    path: 'inbox',
    canActivate: [roleGuard],
    data: { roles: ['ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA'] },
    loadComponent: () =>
      import('./features/expedientes/bandeja/bandeja.component').then((m) => m.BandejaComponent),
  },
  {
    path: 'cases/:id',
    canActivate: [roleGuard],
    data: { roles: ['ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA'] },
    loadComponent: () =>
      import('./features/expedientes/expediente-detail/expediente-detail.component').then(
        (m) => m.ExpedienteDetailComponent,
      ),
  },
  {
    path: 'messages',
    canActivate: [roleGuard],
    data: { roles: ['ANALISTA_SINIESTROS', 'REFERENTE_ASEGURADORA'] },
    loadComponent: () =>
      import('./features/messages/messages.component').then((m) => m.MessagesComponent),
    // A componentless child, not a sibling route: switching conversations must keep the screen (and
    // its list) mounted instead of rebuilding it.
    children: [{ path: ':caseId', children: [] }],
  },
  {
    // No roles in data: any authenticated session gets in, but it is not public in production.
    path: 'styleguide',
    canActivate: [roleGuard],
    loadComponent: () =>
      import('./features/styleguide/styleguide.component').then((m) => m.StyleguideComponent),
  },

  // ----- Insured portal -----
  // No onboardingGuard here (it would redirect to itself); onboardingPendingGuard keeps users who
  // already completed it out.
  {
    path: 'portal/onboarding',
    canActivate: [roleGuard, onboardingPendingGuard],
    data: { roles: ['ASEGURADO'] },
    loadComponent: () =>
      import('./features/portal/onboarding/onboarding.component').then(
        (m) => m.OnboardingComponent,
      ),
  },
  {
    path: 'portal/profile',
    canActivate: [roleGuard, onboardingGuard],
    data: { roles: ['ASEGURADO'] },
    loadComponent: () =>
      import('./features/portal/profile/profile.component').then((m) => m.ProfileComponent),
  },
  {
    path: 'portal/policies',
    canActivate: [roleGuard, onboardingGuard],
    data: { roles: ['ASEGURADO'] },
    loadComponent: () =>
      import('./features/portal/my-policies/my-policies.component').then(
        (m) => m.MyPoliciesComponent,
      ),
  },
  {
    path: 'portal',
    canActivate: [roleGuard, onboardingGuard],
    data: { roles: ['ASEGURADO'] },
    loadComponent: () =>
      import('./features/portal/my-cases/my-cases.component').then((m) => m.MyCasesComponent),
  },
  {
    path: 'portal/messages',
    canActivate: [roleGuard, onboardingGuard],
    data: { roles: ['ASEGURADO'] },
    loadComponent: () =>
      import('./features/portal/my-messages/my-messages.component').then(
        (m) => m.MyMessagesComponent,
      ),
  },
  {
    path: 'portal/cases/:id',
    canActivate: [roleGuard, onboardingGuard],
    data: { roles: ['ASEGURADO'] },
    loadComponent: () =>
      import('./features/portal/case-tracking/case-tracking.component').then(
        (m) => m.CaseTrackingComponent,
      ),
  },
  {
    path: 'portal/cases/:id/documents',
    canActivate: [roleGuard, onboardingGuard],
    data: { roles: ['ASEGURADO'] },
    loadComponent: () =>
      import('./features/portal/case-upload/case-upload.component').then(
        (m) => m.CaseUploadComponent,
      ),
  },
  {
    path: 'new-claim',
    canActivate: [roleGuard, onboardingGuard],
    data: { roles: ['ASEGURADO'] },
    loadComponent: () =>
      import('./features/expedientes/nueva-denuncia/nueva-denuncia.component').then(
        (m) => m.NuevaDenunciaComponent,
      ),
  },

  // ----- Referente panel -----
  {
    path: 'insurer/users',
    canActivate: [roleGuard],
    data: { roles: ['REFERENTE_ASEGURADORA'] },
    loadComponent: () =>
      import('./features/admin/users/users.component').then((m) => m.UsersComponent),
  },
  {
    path: 'insurer/rules',
    canActivate: [roleGuard],
    data: { roles: ['REFERENTE_ASEGURADORA'] },
    loadComponent: () =>
      import('./features/admin/rules/rules.component').then((m) => m.RulesComponent),
  },
  {
    // Settlements above the analyst's authority limit, awaiting the referente's sign-off.
    path: 'insurer/settlements',
    canActivate: [roleGuard],
    data: { roles: ['REFERENTE_ASEGURADORA'] },
    loadComponent: () =>
      import('./features/admin/authorizations/authorizations.component').then(
        (m) => m.AuthorizationsComponent,
      ),
  },
  {
    path: 'insurer/dashboard',
    canActivate: [roleGuard],
    // Operational metrics, not insurer configuration, so the analyst sees them too.
    data: { roles: ['REFERENTE_ASEGURADORA', 'ANALISTA_SINIESTROS'] },
    loadComponent: () =>
      import('./features/admin/dashboard/dashboard.component').then((m) => m.DashboardComponent),
  },
  {
    path: 'insurer/reports',
    canActivate: [roleGuard],
    data: { roles: ['REFERENTE_ASEGURADORA', 'ANALISTA_SINIESTROS'] },
    // Tabs are routes rather than a signal so they can be bookmarked and survive navigating back
    // from a case.
    loadComponent: () =>
      import('./features/admin/reports/reports.component').then((m) => m.ReportsComponent),
    children: [
      // Reopens the last-used tab, not always the first.
      { path: '', pathMatch: 'full', redirectTo: () => rememberedReportsTab() },
      {
        path: 'resolutions',
        loadComponent: () =>
          import('./features/admin/reports/resolution-report.component').then(
            (m) => m.ResolutionReportComponent,
          ),
      },
      {
        path: 'fraud',
        loadComponent: () =>
          import('./features/admin/reports/fraud-report.component').then(
            (m) => m.FraudReportComponent,
          ),
      },
    ],
  },

  // Not redirectTo: '' — Angular does not re-evaluate the resulting redirect, so the '' -> login
  // rule would not fire and unknown URLs would render blank.
  { path: '**', redirectTo: 'login' },
];
