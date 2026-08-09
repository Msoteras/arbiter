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
    'El análisis automático no pudo completarse tras varios intentos. El caso no se pierde: un analista puede reintentar la clasificación a mano.',
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
    'Un analista reintenta la clasificación desde el detalle del expediente y sigue el flujo normal.',
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

/**
 * Texto del gauge de fraude cuando no hay `riskBand`: distingue por qué falta, en vez del
 * "Sin datos" ambiguo. "En proceso" (todavía clasificando), "No aplica" (Fast Track: el modelo
 * no corre) y "Sin evaluar" (el resto). Ver docs/frontend-bugs-ux.md #20.
 */
export function riskBandEmptyLabel(status: string, classification: string | null): string {
  if (status === 'PENDING_CLASSIFICATION') {
    return 'En proceso';
  }
  if (classification === 'FAST_TRACK') {
    return 'No aplica · Fast Track';
  }
  return 'Sin evaluar';
}

// Simplificado a 3 niveles para el asegurado (portal): sin jerga técnica interna.
// El detalle técnico completo (estadoLabel) sigue disponible en el timeline del expediente.
export type EstadoSimplificado = 'DENUNCIADO' | 'EN_TRAMITE' | 'TERMINADO';

const SIMPLIFICADO: Record<CaseStatus, EstadoSimplificado> = {
  PENDING_CLASSIFICATION: 'DENUNCIADO',
  PENDING_ANALYST_REVIEW: 'EN_TRAMITE',
  CLASSIFICATION_FAILED: 'EN_TRAMITE',
  AWAITING_DOCUMENTATION: 'EN_TRAMITE',
  APPROVED: 'TERMINADO',
  REJECTED: 'TERMINADO',
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

// Orden de avance del progreso simplificado. El progreso visible del asegurado es MONÓTONO:
// una vez que el expediente entró "En trámite" no vuelve a "Denunciado", aunque el estado
// técnico interno retroceda a PENDING_CLASSIFICATION. Eso pasa cuando el asegurado sube la
// documentación faltante y cases-service re-encola la clasificación (ver bug #10 del
// relevamiento de UX): sin esto, el stepper regresaba al día 1 como si se hubiera perdido todo.
const SIMPLIFICADO_ORDER: EstadoSimplificado[] = ['DENUNCIADO', 'EN_TRAMITE', 'TERMINADO'];

/**
 * Estado simplificado EFECTIVO: el máximo nivel de avance alcanzado según el historial de
 * transiciones, no el estado técnico instantáneo. `pastStatuses` son los `toStatus` del historial
 * (GET /cases/{id} los trae en `statusHistory`). Garantiza que el progreso del asegurado nunca
 * retroceda.
 */
export function estadoSimplificadoEfectivo(
  currentStatus: string,
  pastStatuses: string[] = [],
): EstadoSimplificado {
  const maxIndex = [currentStatus, ...pastStatuses]
    .map((s) => SIMPLIFICADO_ORDER.indexOf(estadoSimplificado(s)))
    .reduce((max, i) => Math.max(max, i), 0);
  return SIMPLIFICADO_ORDER[maxIndex];
}

/**
 * True cuando el expediente volvió a PENDING_CLASSIFICATION por una carga de documentación del
 * asegurado (no es una denuncia recién ingresada): se detecta porque ya pasó por
 * AWAITING_DOCUMENTATION en su historial. Sirve para no mostrarle el copy de "día 1" a alguien
 * que acaba de subir lo que le pidieron.
 */
export function esReprocesoPorDocumentacion(
  currentStatus: string,
  pastStatuses: string[],
): boolean {
  return (
    currentStatus === 'PENDING_CLASSIFICATION' && pastStatuses.includes('AWAITING_DOCUMENTATION')
  );
}

// Título tranquilizador para el hero del seguimiento (asegurado). Copy orientado a
// la persona, no a la jerga interna — el detalle técnico vive en estadoLabel.
const TITULOS_ASEGURADO: Record<CaseStatus, string> = {
  PENDING_CLASSIFICATION: 'Recibimos tu denuncia',
  PENDING_ANALYST_REVIEW: 'Tu expediente está en análisis',
  CLASSIFICATION_FAILED: 'Tu expediente está en análisis',
  AWAITING_DOCUMENTATION: 'Necesitamos algo de tu parte',
  APPROVED: 'Tu siniestro fue aprobado',
  REJECTED: 'Tu siniestro fue rechazado',
};

export function estadoTituloAsegurado(value: string): string {
  return (TITULOS_ASEGURADO as Record<string, string>)[value] ?? 'Seguimiento de tu expediente';
}

// Copy específico del reproceso tras carga de documentación: reconoce la acción del asegurado
// en vez de repetir el título de día 1 ("Recibimos tu denuncia"). Ver [[esReprocesoPorDocumentacion]].
const TITULO_REPROCESO_DOCUMENTACION = 'Recibimos tu documentación';
const DESCRIPCION_REPROCESO_DOCUMENTACION =
  'Recibimos los documentos que subiste y estamos reevaluando tu caso. Te avisamos ni bien haya novedades.';

export function estadoTituloAseguradoEfectivo(currentStatus: string, pastStatuses: string[]): string {
  return esReprocesoPorDocumentacion(currentStatus, pastStatuses)
    ? TITULO_REPROCESO_DOCUMENTACION
    : estadoTituloAsegurado(currentStatus);
}

// Subtítulo asegurado-safe: NUNCA menciona la clasificación del modelo, el análisis
// automático ni estados técnicos internos (ver [[project-asegurado-vs-analista-visibility]]).
// El asegurado no debe enterarse del pipeline de IA ni del scoring de fraude; solo qué
// pasa con su caso en lenguaje llano. El detalle técnico vive en estadoDescripcion (analista).
const DESCRIPCIONES_ASEGURADO: Record<CaseStatus, string> = {
  PENDING_CLASSIFICATION:
    'Recibimos tu denuncia y la estamos procesando. En breve un analista la revisa.',
  PENDING_ANALYST_REVIEW:
    'Un analista está revisando tu caso. Te avisamos ni bien haya novedades.',
  CLASSIFICATION_FAILED:
    'Un analista está revisando tu caso. No hace falta que hagas nada por ahora.',
  AWAITING_DOCUMENTATION:
    'Necesitamos que subas la documentación faltante para poder continuar.',
  APPROVED: 'Tu siniestro fue aprobado. Vas a recibir el detalle por correo electrónico.',
  REJECTED: 'Tu siniestro fue rechazado. Vas a recibir los motivos por correo electrónico.',
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
