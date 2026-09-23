// Mirrors common-lib's UserRole enum.
export type UserRole = 'ASEGURADO' | 'ANALISTA_SINIESTROS' | 'REFERENTE_ASEGURADORA';

const LABELS: Record<UserRole, string> = {
  ASEGURADO: 'Asegurado',
  ANALISTA_SINIESTROS: 'Analista de siniestros',
  REFERENTE_ASEGURADORA: 'Referente de aseguradora',
};

export function userRoleLabel(value: string): string {
  return (LABELS as Record<string, string>)[value] ?? value;
}

export function homeRouteFor(rol: UserRole): string {
  if (rol === 'ASEGURADO') return '/portal/home';
  if (rol === 'REFERENTE_ASEGURADORA') return '/insurer/home';
  return '/home';
}
