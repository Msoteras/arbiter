import { clasificacionLabel } from './clasificacion';
import { veredictoLabel } from './peritaje';

/**
 * Traduce al español los literales de enum que quedan incrustados en el motivo de una transición
 * de estado ("informe de peritaje recibido: FRAUD_DISCARDED").
 *
 * El motivo lo escribe el backend como prosa ya en español, pero interpola el valor crudo del enum,
 * que por convención del proyecto va en inglés. La traducción se hace acá y no allá por dos razones:
 * es responsabilidad del frontend (CLAUDE.md), y sobre todo porque los motivos **ya están
 * persistidos** — arreglar solo el backend dejaría en inglés todo el historial existente, que es
 * justamente el que se mira.
 *
 * Traduce por token y no por frase completa: el motivo es texto libre y su redacción puede cambiar,
 * pero los literales son un contrato estable. Un token desconocido se deja como está — es preferible
 * a inventarle una traducción o a comerse el dato.
 */

/**
 * Las dos grafías de la decisión conviven en la base: las filas viejas guardaron `APPROVE`/`REJECT`
 * y las nuevas `APROBAR`/`RECHAZAR`. Se mapean las cuatro porque el historial es inmutable y las
 * viejas se siguen leyendo.
 */
const DECISION_LABELS: Record<string, string> = {
  APPROVE: 'Aprobado',
  REJECT: 'Rechazado',
  APROBAR: 'Aprobado',
  RECHAZAR: 'Rechazado',
};

/** Un token candidato: MAYÚSCULAS con guiones bajos, de 4 caracteres para arriba. */
const TOKEN = /[A-Z][A-Z_]{3,}/g;

function tokenLabel(token: string): string {
  const decision = DECISION_LABELS[token];
  if (decision) {
    return decision;
  }
  // Las funciones de label devuelven el valor tal cual cuando no lo conocen, así que se prueban en
  // orden y la primera que reconozca algo gana.
  const clasificacion = clasificacionLabel(token);
  if (clasificacion !== token) {
    return clasificacion;
  }
  return veredictoLabel(token);
}

export function historialNota(reason: string | null | undefined): string {
  if (!reason) {
    return '';
  }
  return reason.replace(TOKEN, (token) => tokenLabel(token));
}
