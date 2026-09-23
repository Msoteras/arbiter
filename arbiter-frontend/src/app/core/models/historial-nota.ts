import { clasificacionLabel } from './clasificacion';
import { repairOutcomeLabel, veredictoLabel } from './peritaje';

// Both spellings exist in the immutable history, so all four are mapped.
const DECISION_LABELS: Record<string, string> = {
  APPROVE: 'Aprobado',
  REJECT: 'Rechazado',
  APROBAR: 'Aprobado',
  RECHAZAR: 'Rechazado',
};

const TOKEN = /[A-Z][A-Z_]{3,}/g;

function tokenLabel(token: string): string {
  const decision = DECISION_LABELS[token];
  if (decision) {
    return decision;
  }
  // Label functions echo unknown values, so the first one that changes the token wins.
  const clasificacion = clasificacionLabel(token);
  if (clasificacion !== token) {
    return clasificacion;
  }
  const veredicto = veredictoLabel(token);
  if (veredicto !== token) {
    return veredicto;
  }
  return repairOutcomeLabel(token);
}

/**
 * Translates enum literals embedded in persisted status-transition reasons
 * ("informe de peritaje recibido: FRAUD_DISCARDED"). Token-based: unknown tokens are left as-is.
 */
export function historialNota(reason: string | null | undefined): string {
  if (!reason) {
    return '';
  }
  return reason.replace(TOKEN, (token) => tokenLabel(token));
}
