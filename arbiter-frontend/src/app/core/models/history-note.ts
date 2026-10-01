import { classificationLabel } from './classification';
import { repairOutcomeLabel, verdictLabel } from './expert-assessment';

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
  const classification = classificationLabel(token);
  if (classification !== token) {
    return classification;
  }
  const veredicto = verdictLabel(token);
  if (veredicto !== token) {
    return veredicto;
  }
  return repairOutcomeLabel(token);
}

/**
 * Translates enum literals embedded in persisted status-transition reasons
 * ("informe de peritaje recibido: FRAUD_DISCARDED"). Token-based: unknown tokens are left as-is.
 */
export function historyNote(reason: string | null | undefined): string {
  if (!reason) {
    return '';
  }
  return reason.replace(TOKEN, (token) => tokenLabel(token));
}
