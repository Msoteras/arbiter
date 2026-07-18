import { StatusTone } from './status-tone';

// Espejo del enum Clasificacion de common-lib
// (ar.edu.utn.frba.arbiter.common.enums.Clasificacion).
// expedientes-service lo devuelve hoy como String en analysisClassification.
export type Clasificacion =
  | 'FAST_TRACK'
  | 'FALTA_DOCUMENTACION'
  | 'LLM_RECOMIENDA_APROBAR'
  | 'LLM_NO_RECOMIENDA_APROBAR'
  | 'LLM_SOLICITA_REVISION_MANUAL'
  | 'POTENCIAL_RIESGO'
  | 'REQUIERE_ANALISIS_MANUAL';

const LABELS: Record<Clasificacion, string> = {
  FAST_TRACK: 'Fast Track',
  FALTA_DOCUMENTACION: 'Falta documentación',
  LLM_RECOMIENDA_APROBAR: 'Recomienda aprobar',
  LLM_NO_RECOMIENDA_APROBAR: 'Recomienda rechazar',
  LLM_SOLICITA_REVISION_MANUAL: 'Requiere revisión manual',
  POTENCIAL_RIESGO: 'Potencial riesgo',
  REQUIERE_ANALISIS_MANUAL: 'Requiere análisis manual',
};

export function clasificacionLabel(value: string): string {
  return (LABELS as Record<string, string>)[value] ?? value;
}

// Tono de semáforo por recomendación del modelo. Recomienda aprobar → ok;
// no recomienda / potencial riesgo → danger; Fast Track → info; falta doc o
// requiere intervención humana → warning. La decisión sigue siendo del analista:
// el color es una señal, no un veredicto.
const TONES: Record<Clasificacion, StatusTone> = {
  FAST_TRACK: 'info',
  FALTA_DOCUMENTACION: 'warning',
  LLM_RECOMIENDA_APROBAR: 'ok',
  LLM_NO_RECOMIENDA_APROBAR: 'danger',
  LLM_SOLICITA_REVISION_MANUAL: 'warning',
  POTENCIAL_RIESGO: 'danger',
  REQUIERE_ANALISIS_MANUAL: 'warning',
};

export function clasificacionTone(value: string): StatusTone {
  return (TONES as Record<string, StatusTone>)[value] ?? 'neutral';
}
