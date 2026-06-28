// Espejo del enum Clasificacion de common-lib
// (ar.edu.utn.frba.arbiter.common.enums.Clasificacion).
// expedientes-service lo devuelve hoy como String en analysisClassification.
export type Clasificacion =
  | 'FAST_TRACK'
  | 'FALTA_DOCUMENTACION'
  | 'POTENCIAL_RIESGO'
  | 'REQUIERE_ANALISIS_MANUAL';

const LABELS: Record<Clasificacion, string> = {
  FAST_TRACK: 'Fast Track',
  FALTA_DOCUMENTACION: 'Falta documentación',
  POTENCIAL_RIESGO: 'Potencial riesgo',
  REQUIERE_ANALISIS_MANUAL: 'Requiere análisis manual',
};

export function clasificacionLabel(value: string): string {
  return (LABELS as Record<string, string>)[value] ?? value;
}
