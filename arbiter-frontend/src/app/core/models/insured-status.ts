import { StatusTone } from './status-tone';

// Mirrors common-lib's InsuredStatus: the case status as the insured is told it. Several internal
// statuses collapse into one, so the portal never learns the classification step or its failures.
export type InsuredStatusCode =
  | 'UNDER_ANALYSIS'
  | 'AWAITING_DOCUMENTATION'
  | 'UNDER_VERIFICATION'
  | 'UNDER_REPAIR'
  | 'APPROVED'
  | 'REJECTED'
  | 'LAPSED'
  | 'CLOSED';

/** Mirrors cases-service's InsuredStatusResponse; `label` comes from the backend ready to show. */
export interface InsuredStatus {
  code: InsuredStatusCode;
  label: string;
}

/** One movement of the history as the insured receives it. `fromInsuredStatus` is null on the filing. */
export interface InsuredMovement {
  fromInsuredStatus: InsuredStatus | null;
  toInsuredStatus: InsuredStatus;
  /** Only tells whether the insured did it themselves; anyone else comes as null. */
  actor: string | null;
  changedAt: string;
}

const FINAL: readonly InsuredStatusCode[] = ['APPROVED', 'REJECTED', 'LAPSED', 'CLOSED'];

export function isFinalInsuredStatus(code: InsuredStatusCode): boolean {
  return FINAL.includes(code);
}

const TONES: Record<InsuredStatusCode, StatusTone> = {
  UNDER_ANALYSIS: 'info',
  AWAITING_DOCUMENTATION: 'warning',
  UNDER_VERIFICATION: 'info',
  UNDER_REPAIR: 'info',
  APPROVED: 'ok',
  REJECTED: 'danger',
  LAPSED: 'danger',
  CLOSED: 'neutral',
};

export function insuredStatusTone(code: InsuredStatusCode): StatusTone {
  return TONES[code] ?? 'neutral';
}

// Never names the model's classification or the expert report itself.
const NEXT_STEPS: Record<InsuredStatusCode, string> = {
  UNDER_ANALYSIS:
    'El analista aprueba o rechaza el caso. El resultado se notifica por correo electrónico.',
  AWAITING_DOCUMENTATION:
    'Subí los documentos faltantes; al recibirlos, el caso se vuelve a evaluar automáticamente.',
  UNDER_VERIFICATION:
    'Cuando termine la verificación, un analista revisa el resultado y te avisamos la resolución.',
  UNDER_REPAIR:
    'Cuando el servicio técnico responda, un analista revisa el resultado y te avisamos la resolución.',
  APPROVED: 'Te enviamos un correo con el detalle de la resolución. No quedan pasos pendientes.',
  REJECTED: 'Te enviamos un correo con los motivos del rechazo. No quedan pasos pendientes.',
  LAPSED: 'No quedan pasos pendientes: el expediente se cerró por caducidad.',
  CLOSED:
    'No quedan pasos pendientes. Si querés continuar con el reclamo, comunicate con nosotros.',
};

export function insuredNextStep(code: InsuredStatusCode): string {
  return NEXT_STEPS[code] ?? '';
}

const TITLES: Record<InsuredStatusCode, string> = {
  UNDER_ANALYSIS: 'Tu siniestro está en análisis',
  AWAITING_DOCUMENTATION: 'Necesitamos algo de tu parte',
  UNDER_VERIFICATION: 'Tu siniestro está con un perito',
  UNDER_REPAIR: 'Tu siniestro está en reparación',
  APPROVED: 'Tu siniestro fue aprobado',
  REJECTED: 'Tu siniestro fue rechazado',
  LAPSED: 'Tu siniestro caducó',
  // Withdrawn or voided: the insured never learns which, only that it ended.
  CLOSED: 'Cerramos tu denuncia',
};

// Must NEVER mention the model's classification, fraud scoring or internal statuses.
const DESCRIPTIONS: Record<InsuredStatusCode, string> = {
  UNDER_ANALYSIS: 'Un analista está revisando tu caso. Te avisamos ni bien haya novedades.',
  AWAITING_DOCUMENTATION: 'Necesitamos que subas la documentación faltante para poder continuar.',
  UNDER_VERIFICATION:
    'Un perito está verificando lo que pasó. Es posible que te contacte para coordinar. Te avisamos ni bien haya novedades.',
  UNDER_REPAIR:
    'Derivamos tu caso a un servicio técnico. Te avisamos ni bien tengamos su respuesta.',
  APPROVED: 'Tu siniestro fue aprobado. Te enviamos el detalle por correo electrónico.',
  REJECTED: 'Tu siniestro fue rechazado. Te enviamos los motivos por correo electrónico.',
  LAPSED:
    'Cerramos tu siniestro por falta de la documentación que te pedimos. Te enviamos el detalle por correo electrónico.',
  CLOSED: 'Cerramos la denuncia de tu siniestro. Te enviamos el detalle por correo electrónico.',
};

const DOCUMENTATION_REWORK_TITLE = 'Recibimos tu documentación';
const DOCUMENTATION_REWORK_DESCRIPTION =
  'Recibimos los documentos que subiste y estamos reevaluando tu caso. Te avisamos ni bien haya novedades.';

/** The last thing that happened is the insured sending documents, and the case is being looked at again. */
export function isDocumentationRework(
  code: InsuredStatusCode,
  history: readonly InsuredMovement[],
): boolean {
  const last = history[history.length - 1];
  return (
    code === 'UNDER_ANALYSIS' &&
    !!last &&
    last.actor === 'INSURED' &&
    last.fromInsuredStatus !== null
  );
}

export function insuredStatusTitle(
  code: InsuredStatusCode,
  history: readonly InsuredMovement[] = [],
): string {
  return isDocumentationRework(code, history)
    ? DOCUMENTATION_REWORK_TITLE
    : (TITLES[code] ?? 'Seguimiento de tu siniestro');
}

export function insuredStatusDescription(
  code: InsuredStatusCode,
  history: readonly InsuredMovement[] = [],
): string {
  return isDocumentationRework(code, history)
    ? DOCUMENTATION_REWORK_DESCRIPTION
    : (DESCRIPTIONS[code] ?? '');
}

/**
 * Names a movement from the insured's side. The backend already sends only the ones they can tell
 * happened, so every movement gets a label.
 */
export function insuredMovementLabel(movement: InsuredMovement): string {
  const from = movement.fromInsuredStatus?.code ?? null;
  if (from === null) {
    return 'Denuncia recibida';
  }

  switch (movement.toInsuredStatus.code) {
    case 'UNDER_ANALYSIS':
      if (movement.actor === 'INSURED') {
        return 'Recibimos tu documentación';
      }
      if (from === 'UNDER_VERIFICATION') {
        return 'Verificación finalizada';
      }
      if (from === 'UNDER_REPAIR') {
        return 'Respuesta del servicio técnico recibida';
      }
      // Named as a reopening, but the reason is never shown.
      if (isFinalInsuredStatus(from)) {
        return 'Reabrimos tu siniestro';
      }
      return 'Un analista está revisando tu caso';
    case 'AWAITING_DOCUMENTATION':
      return 'Te pedimos documentación';
    case 'UNDER_VERIFICATION':
      return 'Enviado a verificación con un perito';
    case 'UNDER_REPAIR':
      return 'Enviado al servicio técnico';
    case 'APPROVED':
      return 'Siniestro aprobado';
    case 'REJECTED':
      return 'Siniestro rechazado';
    case 'LAPSED':
      return 'Siniestro caducado por falta de documentación';
    case 'CLOSED':
      return 'Denuncia cerrada';
  }
}
