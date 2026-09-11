// Espejo de PolicyResponse de cases-service (GET /api/v1/policies).
// Multi-aseguradora: cada póliza trae su aseguradora (insurerId/insurerName).

import { StatusTone } from './status-tone';

/**
 * Una cobertura contratada en la póliza. Son VARIAS: una póliza de celulares cubre robo y hurto,
 * cada una con su propia suma asegurada y franquicia. Cuál aplica lo decide el hecho generador
 * denunciado, y eso lo resuelve el backend.
 */
export interface PolicyCoverage {
  code: string;
  description: string;
  insuredAmount: number;
  /** Franquicia en valor absoluto. */
  deductible: number;
  /** La misma franquicia en puntos porcentuales (10 = 10%), como la da la compañía. */
  deductiblePct: number | null;
}

export interface Policy {
  policyNumber: string;
  insurerId: string;
  insurerName: string;
  insuredName: string;
  insuredId: string;
  contactEmail: string | null;
  contactPhone: string | null;
  branch: string;
  insuredItem: string | null;
  product: string;
  /**
   * ISO con hora (`2026-01-01T00:00:00`): la vigencia de la póliza modelo arranca y termina a una
   * hora exacta ("desde las 12:00 hs del..."), no al filo del día, y el backend la manda completa.
   */
  effectiveFrom: string;
  effectiveTo: string;
  upToDate: boolean;
  /**
   * Suma asegurada y franquicia de la PRIMERA cobertura, solo para el resumen de la tarjeta de
   * póliza. No hay una suma asegurada de la póliza: cualquier cosa que decida algo tiene que
   * mirar `coverages` y quedarse con la que corresponde al hecho denunciado.
   */
  insuredAmount: number;
  deductible: number;
  coverages: PolicyCoverage[];
}

// ───────────────── Vigencia y estado de pago ─────────────────
// Son dos ejes distintos y hay que leerlos por separado: una póliza puede estar vigente y con
// deuda, o al día y ya vencida. Colapsarlos en un solo semáforo pierde justamente el caso que al
// asegurado le importa — "me está cubriendo o no".

export type PolicyValidity = 'vigente' | 'vencida' | 'pendiente';

/**
 * `pendiente` es la póliza ya emitida cuya vigencia todavía no arrancó. Existe: la compañía vende
 * con fecha de inicio futura. Sin este caso, una póliza que empieza el mes que viene se mostraría
 * como "Vigente" y el asegurado creería que ya está cubierto.
 */
export function policyValidity(policy: Policy, now: Date = new Date()): PolicyValidity {
  if (new Date(policy.effectiveTo) < now) {
    return 'vencida';
  }
  return new Date(policy.effectiveFrom) > now ? 'pendiente' : 'vigente';
}

const VALIDITY_LABELS: Record<PolicyValidity, string> = {
  vigente: 'Vigente',
  vencida: 'Vencida',
  pendiente: 'Aún no vigente',
};

// `danger` para vencida, igual que el expediente caducado (LAPSED en estado.ts): para el asegurado
// significan lo mismo, que eso ya no lo cubre.
const VALIDITY_TONES: Record<PolicyValidity, StatusTone> = {
  vigente: 'ok',
  vencida: 'danger',
  pendiente: 'info',
};

export function policyValidityLabel(policy: Policy, now?: Date): string {
  return VALIDITY_LABELS[policyValidity(policy, now)];
}

export function policyValidityTone(policy: Policy, now?: Date): StatusTone {
  return VALIDITY_TONES[policyValidity(policy, now)];
}

/** `upToDate` es estado de PAGO (sin cuotas impagas ni saldo), no vigencia. */
export function policyPaymentLabel(policy: Policy): string {
  return policy.upToDate ? 'Al día' : 'Con deuda';
}

// `warning` y no `danger`: una cuota atrasada no anula la póliza por sí sola, es algo que el
// asegurado puede resolver. Quien decide si la deuda afecta la cobertura es el motor de reglas.
export function policyPaymentTone(policy: Policy): StatusTone {
  return policy.upToDate ? 'ok' : 'warning';
}
