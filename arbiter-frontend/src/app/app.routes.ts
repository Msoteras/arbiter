import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'bandeja', pathMatch: 'full' },

  // ----- Portal del analista -----
  {
    path: 'bandeja',
    loadComponent: () =>
      import('./features/expedientes/bandeja/bandeja.component').then((m) => m.BandejaComponent),
  },
  {
    path: 'expedientes/:id',
    loadComponent: () =>
      import('./features/expedientes/expediente-detail/expediente-detail.component').then(
        (m) => m.ExpedienteDetailComponent,
      ),
  },

  // ----- Portal del asegurado -----
  {
    path: 'portal',
    loadComponent: () =>
      import('./features/portal/mis-expedientes/mis-expedientes.component').then(
        (m) => m.MisExpedientesComponent,
      ),
  },
  {
    path: 'portal/expedientes/:id',
    loadComponent: () =>
      import('./features/portal/seguimiento/seguimiento.component').then(
        (m) => m.SeguimientoComponent,
      ),
  },
  {
    path: 'nueva-denuncia',
    loadComponent: () =>
      import('./features/expedientes/nueva-denuncia/nueva-denuncia.component').then(
        (m) => m.NuevaDenunciaComponent,
      ),
  },

  { path: '**', redirectTo: '' },
];
