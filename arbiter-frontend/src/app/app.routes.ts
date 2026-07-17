import { Routes } from '@angular/router';

import { roleGuard } from './core/auth/role.guard';

export const routes: Routes = [
  { path: '', redirectTo: 'login', pathMatch: 'full' },

  {
    path: 'login',
    loadComponent: () =>
      import('./features/auth/login/login.component').then((m) => m.LoginComponent),
  },

  // ----- Portal del analista -----
  {
    path: 'bandeja',
    canActivate: [roleGuard],
    data: { roles: ['ANALISTA_SINIESTROS'] },
    loadComponent: () =>
      import('./features/expedientes/bandeja/bandeja.component').then((m) => m.BandejaComponent),
  },
  {
    path: 'expedientes/:id',
    canActivate: [roleGuard],
    data: { roles: ['ANALISTA_SINIESTROS'] },
    loadComponent: () =>
      import('./features/expedientes/expediente-detail/expediente-detail.component').then(
        (m) => m.ExpedienteDetailComponent,
      ),
  },

  // ----- Portal del asegurado -----
  {
    path: 'portal',
    canActivate: [roleGuard],
    data: { roles: ['ASEGURADO'] },
    loadComponent: () =>
      import('./features/portal/mis-expedientes/mis-expedientes.component').then(
        (m) => m.MisExpedientesComponent,
      ),
  },
  {
    path: 'portal/expedientes/:id',
    canActivate: [roleGuard],
    data: { roles: ['ASEGURADO'] },
    loadComponent: () =>
      import('./features/portal/seguimiento/seguimiento.component').then(
        (m) => m.SeguimientoComponent,
      ),
  },
  {
    path: 'nueva-denuncia',
    canActivate: [roleGuard],
    data: { roles: ['ASEGURADO'] },
    loadComponent: () =>
      import('./features/expedientes/nueva-denuncia/nueva-denuncia.component').then(
        (m) => m.NuevaDenunciaComponent,
      ),
  },

  { path: '**', redirectTo: '' },
];
