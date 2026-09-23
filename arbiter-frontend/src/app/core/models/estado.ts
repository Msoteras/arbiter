import { StatusTone } from './status-tone';

// Mirrors common-lib's CaseStatus enum; cases-service sends it as a string in `status`.
export type CaseStatus =
  | 'PENDING_CLASSIFICATION'
  | 'PENDING_ANALYST_REVIEW'
  | 'CLASSIFICATION_FAILED'
  | 'AWAITING_DOCUMENTATION'
  | 'PENDING_EXPERT_REPORT'
  | 'PENDING_REPAIR'
  | 'APPROVED'
  | 'REJECTED'
  | 'LAPSED';

const LABELS: Record<CaseStatus, string> = {
  PENDING_CLASSIFICATION: 'Pendiente de clasificación',
  PENDING_ANALYST_REVIEW: 'Pendiente de revisión',
  CLASSIFICATION_FAILED: 'Clasificación fallida',
  AWAITING_DOCUMENTATION: 'Falta documentación',
  PENDING_EXPERT_REPORT: 'Derivado a peritaje',
  PENDING_REPAIR: 'Derivado a reparación',
  APPROVED: 'Aprobado',
  REJECTED: 'Rechazado',
  LAPSED: 'Caducado',
};

export function estadoLabel(value: string): string {
  return (LABELS as Record<string, string>)[value] ?? value;
}

// Insured-facing badge: analyst review and classification failure both read "En análisis" so no
// internal jargon leaks. LAPSED stays distinct from REJECTED: it expired, nobody rejected it.
const BADGE_ASEGURADO: Record<CaseStatus, string> = {
  PENDING_CLASSIFICATION: 'Recibido',
  PENDING_ANALYST_REVIEW: 'En análisis',
  CLASSIFICATION_FAILED: 'En análisis',
  AWAITING_DOCUMENTATION: 'Falta documentación',
  PENDING_EXPERT_REPORT: 'En verificación',
  PENDING_REPAIR: 'En reparación',
  APPROVED: 'Aprobado',
  REJECTED: 'Rechazado',
  LAPSED: 'Caducado',
};

export function estadoBadgeLabelAsegurado(value: string): string {
  return (BADGE_ASEGURADO as Record<string, string>)[value] ?? estadoLabel(value);
}

/**
 * Mirrors `case_status.is_final` and the backend's `CaseServiceImpl.FINAL_STATUS_NAMES`.
 * Exported as a list because dashboards count "resolved" cases by summing these statuses.
 */
export const ESTADOS_FINALES: readonly CaseStatus[] = ['APPROVED', 'REJECTED', 'LAPSED'];

export function isEstadoFinal(value: string): boolean {
  return (ESTADOS_FINALES as readonly string[]).includes(value);
}

// Read by the insured: never names the model's classification or the expert report itself.
const PROXIMOS_PASOS: Record<CaseStatus, string> = {
  PENDING_CLASSIFICATION:
    'En pocos minutos el caso pasa a revisión de un analista (o se pide documentación si falta algo).',
  PENDING_ANALYST_REVIEW:
    'El analista aprueba o rechaza el caso. El resultado se notifica por correo electrónico.',
  CLASSIFICATION_FAILED:
    'Un analista está revisando tu caso. No hace falta que hagas nada por ahora.',
  AWAITING_DOCUMENTATION:
    'Subí los documentos faltantes; al recibirlos, el caso se vuelve a evaluar automáticamente.',
  PENDING_EXPERT_REPORT:
    'Cuando termine la verificación, un analista revisa el resultado y te avisamos la resolución.',
  PENDING_REPAIR:
    'Cuando el servicio técnico responda, un analista revisa el resultado y te avisamos la resolución.',
  APPROVED: 'Te enviamos un correo con el detalle de la resolución. No quedan pasos pendientes.',
  REJECTED: 'Te enviamos un correo con los motivos del rechazo. No quedan pasos pendientes.',
  LAPSED: 'No quedan pasos pendientes: el expediente se cerró por caducidad.',
};

export function proximoPaso(value: string): string {
  return (PROXIMOS_PASOS as Record<string, string>)[value] ?? '';
}

const TONES: Record<CaseStatus, StatusTone> = {
  PENDING_CLASSIFICATION: 'info',
  PENDING_ANALYST_REVIEW: 'info',
  CLASSIFICATION_FAILED: 'warning',
  AWAITING_DOCUMENTATION: 'warning',
  // Waiting on an external third party with nothing for the analyst to do, so not a warning.
  PENDING_EXPERT_REPORT: 'info',
  PENDING_REPAIR: 'info',
  APPROVED: 'ok',
  REJECTED: 'danger',
  LAPSED: 'danger',
};

export function estadoTone(value: string): StatusTone {
  return (TONES as Record<string, StatusTone>)[value] ?? 'neutral';
}

/** Fraud gauge text when there is no `riskBand`, telling apart why it is missing. */
export function riskBandEmptyLabel(status: string, classification: string | null): string {
  if (status === 'PENDING_CLASSIFICATION') {
    return 'En proceso';
  }
  if (classification === 'FAST_TRACK') {
    return 'No aplica · Fast Track';
  }
  return 'Sin evaluar';
}

// Three-level progress shown to the insured, free of internal jargon.
export type EstadoSimplificado = 'DENUNCIADO' | 'EN_TRAMITE' | 'TERMINADO';

const SIMPLIFICADO: Record<CaseStatus, EstadoSimplificado> = {
  PENDING_CLASSIFICATION: 'DENUNCIADO',
  PENDING_ANALYST_REVIEW: 'EN_TRAMITE',
  CLASSIFICATION_FAILED: 'EN_TRAMITE',
  AWAITING_DOCUMENTATION: 'EN_TRAMITE',
  PENDING_EXPERT_REPORT: 'EN_TRAMITE',
  PENDING_REPAIR: 'EN_TRAMITE',
  APPROVED: 'TERMINADO',
  REJECTED: 'TERMINADO',
  LAPSED: 'TERMINADO',
};

const SIMPLIFICADO_LABELS: Record<EstadoSimplificado, string> = {
  DENUNCIADO: 'Denunciado',
  EN_TRAMITE: 'En trámite',
  TERMINADO: 'Terminado',
};

export function estadoSimplificado(value: string): EstadoSimplificado {
  return (SIMPLIFICADO as Record<string, EstadoSimplificado>)[value] ?? 'EN_TRAMITE';
}

export function estadoSimplificadoLabel(value: string): string {
  return SIMPLIFICADO_LABELS[estadoSimplificado(value)];
}

export const ESTADOS_SIMPLIFICADOS = Object.entries(SIMPLIFICADO_LABELS).map(([value, label]) => ({
  value: value as EstadoSimplificado,
  label,
}));

export function estadosDelCajon(cajon: EstadoSimplificado): CaseStatus[] {
  return (Object.keys(SIMPLIFICADO) as CaseStatus[]).filter(
    (status) => SIMPLIFICADO[status] === cajon,
  );
}

const SIMPLIFICADO_ORDER: EstadoSimplificado[] = ['DENUNCIADO', 'EN_TRAMITE', 'TERMINADO'];

/**
 * Highest progress level reached across the status history, so the insured's stepper never goes
 * back to "Denunciado" when uploading documents re-queues the case to PENDING_CLASSIFICATION.
 * `pastStatuses` are the `toStatus` values of `statusHistory`.
 */
export function estadoSimplificadoEfectivo(
  currentStatus: string,
  pastStatuses: string[] = [],
): EstadoSimplificado {
  const maxIndex = [currentStatus, ...pastStatuses]
    .map((s) => SIMPLIFICADO_ORDER.indexOf(estadoSimplificado(s)))
    .reduce((max, i) => Math.max(max, i), 0);
  // "Terminado" only while final right now: a reopened case must step back to "En trámite".
  const cap = isEstadoFinal(currentStatus) ? 2 : 1;
  return SIMPLIFICADO_ORDER[Math.min(maxIndex, cap)];
}

/**
 * True when the case is back in PENDING_CLASSIFICATION because the insured uploaded missing
 * documents (it already went through AWAITING_DOCUMENTATION), not because it was just filed.
 */
export function esReprocesoPorDocumentacion(
  currentStatus: string,
  pastStatuses: string[],
): boolean {
  return (
    currentStatus === 'PENDING_CLASSIFICATION' && pastStatuses.includes('AWAITING_DOCUMENTATION')
  );
}

const TITULOS_ASEGURADO: Record<CaseStatus, string> = {
  PENDING_CLASSIFICATION: 'Recibimos tu denuncia',
  PENDING_ANALYST_REVIEW: 'Tu siniestro está en análisis',
  CLASSIFICATION_FAILED: 'Tu siniestro está en análisis',
  AWAITING_DOCUMENTATION: 'Necesitamos algo de tu parte',
  PENDING_EXPERT_REPORT: 'Tu siniestro está en verificación',
  PENDING_REPAIR: 'Tu siniestro está en reparación',
  APPROVED: 'Tu siniestro fue aprobado',
  REJECTED: 'Tu siniestro fue rechazado',
  LAPSED: 'Tu siniestro caducó',
};

export function estadoTituloAsegurado(value: string): string {
  return (TITULOS_ASEGURADO as Record<string, string>)[value] ?? 'Seguimiento de tu siniestro';
}

const TITULO_REPROCESO_DOCUMENTACION = 'Recibimos tu documentación';
const DESCRIPCION_REPROCESO_DOCUMENTACION =
  'Recibimos los documentos que subiste y estamos reevaluando tu caso. Te avisamos ni bien haya novedades.';

export function estadoTituloAseguradoEfectivo(
  currentStatus: string,
  pastStatuses: string[],
): string {
  return esReprocesoPorDocumentacion(currentStatus, pastStatuses)
    ? TITULO_REPROCESO_DOCUMENTACION
    : estadoTituloAsegurado(currentStatus);
}

// Insured-safe copy: must NEVER mention the model's classification, fraud scoring or internal statuses.
const DESCRIPCIONES_ASEGURADO: Record<CaseStatus, string> = {
  PENDING_CLASSIFICATION:
    'Recibimos tu denuncia y la estamos procesando. En breve un analista la revisa.',
  PENDING_ANALYST_REVIEW: 'Un analista está revisando tu caso. Te avisamos ni bien haya novedades.',
  CLASSIFICATION_FAILED:
    'Un analista está revisando tu caso. No hace falta que hagas nada por ahora.',
  AWAITING_DOCUMENTATION: 'Necesitamos que subas la documentación faltante para poder continuar.',
  PENDING_EXPERT_REPORT:
    'Estamos verificando lo que pasó. Es posible que te contacten para coordinar. Te avisamos ni bien haya novedades.',
  PENDING_REPAIR:
    'Derivamos tu caso a un servicio técnico. Te avisamos ni bien tengamos su respuesta.',
  APPROVED: 'Tu siniestro fue aprobado. Te enviamos el detalle por correo electrónico.',
  REJECTED: 'Tu siniestro fue rechazado. Te enviamos los motivos por correo electrónico.',
  LAPSED:
    'Cerramos tu siniestro por falta de la documentación que te pedimos. Te enviamos el detalle por correo electrónico.',
};

export function estadoDescripcionAsegurado(value: string): string {
  return (DESCRIPCIONES_ASEGURADO as Record<string, string>)[value] ?? '';
}

export function estadoDescripcionAseguradoEfectivo(
  currentStatus: string,
  pastStatuses: string[],
): string {
  return esReprocesoPorDocumentacion(currentStatus, pastStatuses)
    ? DESCRIPCION_REPROCESO_DOCUMENTACION
    : estadoDescripcionAsegurado(currentStatus);
}

/**
 * Names each case movement from the insured's side. Maps the STATUS, never the history `reason`:
 * that text is internal and would leak the classification, verdict and referral motive.
 * Returns `null` for movements the insured must not see (e.g. CLASSIFICATION_FAILED).
 */
export function movimientoAseguradoLabel(
  toStatus: string,
  fromStatus: string | null,
): string | null {
  // Analyst-assignment rows are stored with from == to; they are not case movements.
  if (fromStatus !== null && fromStatus === toStatus) {
    return null;
  }

  switch (toStatus) {
    case 'PENDING_CLASSIFICATION':
      if (fromStatus === null) {
        return 'Denuncia recibida';
      }
      // Manual retry after a failed classification: the insured sent nothing.
      if (fromStatus === 'CLASSIFICATION_FAILED') {
        return null;
      }
      return 'Recibimos tu documentación';
    case 'AWAITING_DOCUMENTATION':
      return 'Te pedimos documentación';
    case 'PENDING_ANALYST_REVIEW':
      if (fromStatus === 'PENDING_EXPERT_REPORT') {
        return 'Verificación finalizada';
      }
      if (fromStatus === 'PENDING_REPAIR') {
        return 'Respuesta del servicio técnico recibida';
      }
      // Reopening a resolved case: named as such, but the reason is never shown.
      if (fromStatus !== null && isEstadoFinal(fromStatus)) {
        return 'Reabrimos tu siniestro';
      }
      return 'Un analista está revisando tu caso';
    case 'PENDING_EXPERT_REPORT':
      return 'Enviado a verificación con un perito';
    case 'PENDING_REPAIR':
      return 'Enviado al servicio técnico';
    case 'APPROVED':
      return 'Siniestro aprobado';
    case 'REJECTED':
      return 'Siniestro rechazado';
    case 'LAPSED':
      return 'Siniestro caducado por falta de documentación';
    default:
      return null;
  }
}
