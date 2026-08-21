import { StatusTone } from './status-tone';

/**
 * Espejo del enum FraudRecordSource de common-lib
 * (ar.edu.utn.frba.arbiter.common.enums.FraudRecordSource).
 */
export type OrigenAntecedente = 'EXPERT_BACKED' | 'ANALYST_DECLARED';

const ORIGEN_LABELS: Record<OrigenAntecedente, string> = {
  EXPERT_BACKED: 'Con respaldo pericial',
  ANALYST_DECLARED: 'Declarado por el analista',
};

export function origenAntecedenteLabel(value: string): string {
  return (ORIGEN_LABELS as Record<string, string>)[value] ?? value;
}

/**
 * Espejo de FraudRecordResponse.
 *
 * Los dos booleanos dicen cosas distintas y la pantalla tiene que poder separarlas: `inForce` es
 * si el antecedente sigue dentro de la ventana que configuró la aseguradora, y `scores` es si
 * además pesa en el motor — lo que exige respaldo pericial. Un antecedente vigente sin peritaje
 * es una alerta para el analista y nada más; mostrarlo igual que uno pericial daría a entender
 * que movió el score cuando no lo hizo.
 */
export interface AntecedenteFraude {
  id: number;
  insuredDni: string;
  caseId: number;
  source: OrigenAntecedente;
  reason: string;
  expertAssessmentId: number | null;
  declaredByAnalystName: string;
  declaredAt: string;
  inForce: boolean;
  scores: boolean;
}

/**
 * Los tres estados posibles, que no son intercambiables:
 * - `pericial`: vigente y con peritaje detrás. Es el único que puntúa y puede vetar el Fast Track.
 * - `declarado`: vigente pero sin peritaje. Se muestra, no cuenta.
 * - `vencido`: quedó fuera de la ventana de la aseguradora. Se sigue mostrando, porque "hubo uno
 *   y ya no cuenta" no es lo mismo que "no hubo ninguno".
 */
export type EstadoAntecedente = 'pericial' | 'declarado' | 'vencido';

export function estadoAntecedente(a: AntecedenteFraude): EstadoAntecedente {
  if (!a.inForce) {
    return 'vencido';
  }
  return a.scores ? 'pericial' : 'declarado';
}

const ESTADO_LABELS: Record<EstadoAntecedente, string> = {
  pericial: 'Vigente · con respaldo pericial',
  declarado: 'Vigente · sin respaldo pericial',
  vencido: 'Fuera de la ventana de vigencia',
};

export function estadoAntecedenteLabel(a: AntecedenteFraude): string {
  return ESTADO_LABELS[estadoAntecedente(a)];
}

/**
 * El semáforo acompaña cuánto pesa, no cuán grave suena: `danger` solo cuando el antecedente
 * efectivamente entra al motor. Un antecedente declarado en rojo pleno haría que el analista lo
 * lea como evidencia, que es justo lo que no es.
 */
const ESTADO_TONES: Record<EstadoAntecedente, StatusTone> = {
  pericial: 'danger',
  declarado: 'warning',
  vencido: 'neutral',
};

export function estadoAntecedenteTone(a: AntecedenteFraude): StatusTone {
  return ESTADO_TONES[estadoAntecedente(a)];
}

/** Lo que la pantalla explica debajo del antecedente: qué efecto tiene, en una línea. */
const ESTADO_EFECTOS: Record<EstadoAntecedente, string> = {
  pericial: 'Suma al nivel de riesgo y puede impedir la vía rápida.',
  declarado: 'No suma al nivel de riesgo: se muestra como alerta porque no tuvo peritaje detrás.',
  vencido: 'Ya no pesa: pasó la ventana de vigencia que configuró la aseguradora.',
};

export function efectoAntecedente(a: AntecedenteFraude): string {
  return ESTADO_EFECTOS[estadoAntecedente(a)];
}

/** Lo que el analista manda al registrar. El motivo tiene un mínimo real, igual que en el back. */
export interface RegistrarAntecedenteRequest {
  source: OrigenAntecedente;
  reason: string;
}

/** Mismo mínimo que valida cases-service; acá evita el viaje de ida y vuelta. */
export const MOTIVO_ANTECEDENTE_MIN = 20;
