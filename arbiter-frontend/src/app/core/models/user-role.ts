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
