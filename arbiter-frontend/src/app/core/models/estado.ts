import { StatusTone } from './status-tone';

// Espejo del enum CaseStatus de common-lib
// (ar.edu.utn.frba.arbiter.common.enums.CaseStatus).
// cases-service lo devuelve como String en el campo `status`.
export type CaseStatus =
  | 'PENDING_CLASSIFICATION'
  | 'PENDING_ANALYST_REVIEW'
  | 'CLASSIFICATION_FAILED'
  | 'AWAITING_DOCUMENTATION'
  | 'APPROVED'
  | 'REJECTED';

const LABELS: Record<CaseStatus, string> = {
  PENDING_CLASSIFICATION: 'Pendiente de clasificación',
  PENDING_ANALYST_REVIEW: 'Pendiente de revisión',
  CLASSIFICATION_FAILED: 'Clasificación fallida',
  AWAITING_DOCUMENTATION: 'Falta documentación',
  APPROVED: 'Aprobado',
  REJECTED: 'Rechazado',
};

export function estadoLabel(value: string): string {
  return (LABELS as Record<string, string>)[value] ?? value;
}

/** Estados terminales: el expediente ya tiene resolución, no hay próximos pasos. */
export function isEstadoFinal(value: string): boolean {
  return value === 'APPROVED' || value === 'REJECTED';
}

// Qué significa cada estado para quien sigue el expediente (asegurado o analista).
const DESCRIPCIONES: Record<CaseStatus, string> = {
  PENDING_CLASSIFICATION:
    'La denuncia fue registrada y el sistema está generando una clasificación preliminar.',
  PENDING_ANALYST_REVIEW:
    'La clasificación preliminar ya está disponible y un analista de siniestros está revisando el caso.',
  CLASSIFICATION_FAILED:
    'El análisis automático tuvo un inconveniente técnico. El caso no se pierde: va a ser reprocesado.',
  AWAITING_DOCUMENTATION:
    'Falta documentación obligatoria para poder evaluar el caso.',
  APPROVED: 'El siniestro fue aprobado por un analista.',
  REJECTED: 'El siniestro fue rechazado por un analista.',
};

// Próximo paso esperado desde cada estado. Refleja el flujo real del backend:
// clasificación asincrónica → revisión del analista → resolución + mail (SendGrid).
const PROXIMOS_PASOS: Record<CaseStatus, string> = {
  PENDING_CLASSIFICATION:
    'En pocos minutos el caso pasa a revisión de un analista (o se pide documentación si falta algo).',
  PENDING_ANALYST_REVIEW:
    'El analista aprueba o rechaza el caso. El resultado se notifica por correo electrónico.',
  CLASSIFICATION_FAILED:
    'El equipo reintenta el análisis. No hace falta hacer nada.',
  AWAITING_DOCUMENTATION:
    'Subí los documentos faltantes; al recibirlos, el caso se vuelve a evaluar automáticamente.',
  APPROVED:
    'Vas a recibir un correo con el detalle de la resolución. No quedan pasos pendientes.',
  REJECTED:
    'Vas a recibir un correo con los motivos del rechazo. No quedan pasos pendientes.',
};

export function estadoDescripcion(value: string): string {
  return (DESCRIPCIONES as Record<string, string>)[value] ?? '';
}

export function proximoPaso(value: string): string {
  return (PROXIMOS_PASOS as Record<string, string>)[value] ?? '';
}

// Tono de semáforo por estado. En curso → info; falta algo / falla técnica →
// warning; resolución a favor → ok; resolución en contra → danger.
const TONES: Record<CaseStatus, StatusTone> = {
  PENDING_CLASSIFICATION: 'info',
  PENDING_ANALYST_REVIEW: 'info',
  CLASSIFICATION_FAILED: 'warning',
  AWAITING_DOCUMENTATION: 'warning',
  APPROVED: 'ok',
  REJECTED: 'danger',
};

export function estadoTone(value: string): StatusTone {
  return (TONES as Record<string, StatusTone>)[value] ?? 'neutral';
}
