import { StatusTone } from './status-tone';

// Mirrors common-lib's Classification enum.
export type Classification =
  | 'FAST_TRACK'
  | 'FALTA_DOCUMENTACION'
  | 'LLM_RECOMIENDA_APROBAR'
  | 'LLM_NO_RECOMIENDA_APROBAR'
  | 'LLM_SOLICITA_REVISION_MANUAL';

const LABELS: Record<Classification, string> = {
  FAST_TRACK: 'Fast Track',
  FALTA_DOCUMENTACION: 'Falta documentación',
  LLM_RECOMIENDA_APROBAR: 'Recomienda aprobar',
  LLM_NO_RECOMIENDA_APROBAR: 'Recomienda rechazar',
  LLM_SOLICITA_REVISION_MANUAL: 'Requiere revisión manual',
};

export function classificationLabel(value: string): string {
  return (LABELS as Record<string, string>)[value] ?? value;
}

const TONES: Record<Classification, StatusTone> = {
  FAST_TRACK: 'info',
  FALTA_DOCUMENTACION: 'warning',
  LLM_RECOMIENDA_APROBAR: 'ok',
  LLM_NO_RECOMIENDA_APROBAR: 'danger',
  LLM_SOLICITA_REVISION_MANUAL: 'warning',
};

export function classificationTone(value: string): StatusTone {
  return (TONES as Record<string, StatusTone>)[value] ?? 'neutral';
}
