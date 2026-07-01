import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'bandeja', pathMatch: 'full' },
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
  { path: '**', redirectTo: '' },
];
