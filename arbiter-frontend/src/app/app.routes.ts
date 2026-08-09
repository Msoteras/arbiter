import { Routes } from '@angular/router';

import { roleGuard } from './core/auth/role.guard';

export const routes: Routes = [
  { path: '', redirectTo: 'login', pathMatch: 'full' },

  {
    path: 'login',
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

  // ----- Portal del analista (la bandeja y el detalle también los ve el referente, en
  //        modo solo lectura: sin asignar ni decidir) -----
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
    // Sin roles en data: cualquier sesión autenticada entra (no es de un rol en particular,
    // es la vitrina del design system) — pero sí requiere login, no queda pública en
    // producción. docs/frontend-bugs-ux.md #18.
    path: 'styleguide',
    canActivate: [roleGuard],
    loadComponent: () =>
      import('./features/styleguide/styleguide.component').then((m) => m.StyleguideComponent),
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
    path: 'portal/cases/:id',
    canActivate: [roleGuard],
    data: { roles: ['ASEGURADO'] },
    loadComponent: () =>
      import('./features/portal/seguimiento/seguimiento.component').then(
        (m) => m.SeguimientoComponent,
      ),
  },
  {
    path: 'portal/cases/:id/documentacion',
    canActivate: [roleGuard],
    data: { roles: ['ASEGURADO'] },
    loadComponent: () =>
      import('./features/portal/documentacion/documentacion.component').then(
        (m) => m.DocumentacionComponent,
      ),
  },
  {
    path: 'new-claim',
    canActivate: [roleGuard],
    data: { roles: ['ASEGURADO'] },
    loadComponent: () =>
      import('./features/expedientes/nueva-denuncia/nueva-denuncia.component').then(
        (m) => m.NuevaDenunciaComponent,
      ),
  },

  // ----- Panel del referente -----
  {
    path: 'insurer/users',
    canActivate: [roleGuard],
    data: { roles: ['REFERENTE_ASEGURADORA'] },
    loadComponent: () =>
      import('./features/admin/usuarios/usuarios.component').then((m) => m.UsuariosComponent),
  },
  {
    path: 'insurer/rules',
    canActivate: [roleGuard],
    data: { roles: ['REFERENTE_ASEGURADORA'] },
    loadComponent: () =>
      import('./features/admin/reglas/reglas.component').then((m) => m.ReglasComponent),
  },
  {
    path: 'insurer/dashboard',
    canActivate: [roleGuard],
    data: { roles: ['REFERENTE_ASEGURADORA'] },
    loadComponent: () =>
      import('./features/admin/dashboard/dashboard.component').then((m) => m.DashboardComponent),
  },
  {
    path: 'insurer/reports',
    canActivate: [roleGuard],
    data: { roles: ['REFERENTE_ASEGURADORA'] },
    loadComponent: () =>
      import('./features/admin/reportes/reportes.component').then((m) => m.ReportesComponent),
  },

  // redirectTo: '' no vuelve a disparar la regla '' → login (Angular no re-evalúa el
  // redirect resultante) — una URL inexistente quedaba en pantalla en blanco, sin
  // mensaje ni forma de volver. docs/frontend-bugs-ux.md #5.
  { path: '**', redirectTo: 'login' },
];
