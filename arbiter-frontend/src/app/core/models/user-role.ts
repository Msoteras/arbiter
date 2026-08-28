// Espejo del enum UserRole de common-lib (ar.edu.utn.frba.arbiter.common.enums.UserRole).
export type UserRole = 'ASEGURADO' | 'ANALISTA_SINIESTROS' | 'REFERENTE_ASEGURADORA';

const LABELS: Record<UserRole, string> = {
  ASEGURADO: 'Asegurado',
  ANALISTA_SINIESTROS: 'Analista de siniestros',
  REFERENTE_ASEGURADORA: 'Referente de aseguradora',
};

export function userRoleLabel(value: string): string {
  return (LABELS as Record<string, string>)[value] ?? value;
}

/** Cada rol aterriza en su propio home, que resume su trabajo y linkea al resto de su sección. */
export function homeRouteFor(rol: UserRole): string {
  if (rol === 'ASEGURADO') return '/portal/home';
  if (rol === 'REFERENTE_ASEGURADORA') return '/insurer/home';
  return '/home';
}
