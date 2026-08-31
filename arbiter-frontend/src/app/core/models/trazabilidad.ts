import {
  HARD_RULE_LABELS,
  INSURER_HARD_RULE_LABELS,
} from '../../features/admin/hard-rules.service';
import { StatusTone } from './status-tone';

/** Mirror of RuleResultResponse (common-lib). Passes travel too, not just the rejections. */
export interface RuleResult {
  id: number;
  ruleType: string;
  /** `PASS` | `FAIL`. */
  result: string;
  evaluatedValue: string | null;
  scoreContribution: number | null;
  evaluatedAt: string;
}

// The six editable ones reuse the rules screen's labels so they can't drift apart.
const RULE_TYPE_LABELS: Record<string, string> = {
  ...HARD_RULE_LABELS,
  ...INSURER_HARD_RULE_LABELS,
  COVERAGE_EXCLUSION: 'Exclusión de cobertura',
  COVERAGE_INCLUSION: 'Alcance de la cobertura',
  // Las dos de alcance (D9) se configuran en la cobertura, no en la solapa de reglas duras, así
  // que no tienen label del que colgarse arriba.
  COVERS_FAMILY_GROUP: 'Alcance al grupo familiar',
  CLAIM_EXHAUSTS_COVERAGE: 'Cobertura consumida por un siniestro previo',
  FRAUD_RECORD: 'Antecedente de fraude',
  // The engine never writes it (the gate leaves no rule_result), but the demo seed does.
  FAST_TRACK: 'Criterio de Fast Track',
};

export function ruleTypeLabel(ruleType: string): string {
  return RULE_TYPE_LABELS[ruleType] ?? ruleType;
}

// El motor escribe PASS/FAIL y es el único vocabulario: los CUMPLE/NO_CUMPLE de un seed viejo se
// migraron en los datos (db/migrations/2026-08-30-rule-result-literales-en-ingles.sql).
// Cualquier otro literal se muestra tal cual y sin tono: dar por fallada una regla que no
// reconocemos sería acusarla de algo que quizás cumplió.
export function ruleResultLabel(result: string): string {
  if (result === 'PASS') return 'Cumple';
  return result === 'FAIL' ? 'No cumple' : result;
}

export function ruleResultTone(result: string): StatusTone {
  if (result === 'PASS') return 'ok';
  return result === 'FAIL' ? 'danger' : 'neutral';
}

/**
 * evaluated_value arrives as the engine writes it, in key=value pairs. That literal is the audit
 * record and stays untouched in the DB; the analyst gets the sentence. Same numbers, nothing is
 * recomputed here.
 */
const DAMNIFICADO: Record<string, string> = {
  TITULAR: 'el titular',
  FAMILIAR: 'un familiar',
  TERCERO: 'un tercero',
};

export function ruleEvaluationText(ruleType: string, evaluatedValue: string | null): string {
  if (!evaluatedValue) {
    return '—';
  }
  const t = tokens(evaluatedValue);
  switch (ruleType) {
    case 'POLICY_IN_FORCE': {
      const [desde, hasta] = (t['coverageWindow'] ?? '').split('..');
      return desde && hasta
        ? `Hecho del ${t['eventDate']} · vigencia del ${desde} al ${hasta}`
        : evaluatedValue;
    }
    case 'WAITING_PERIOD': {
      const carencia = /^(\d+)d from (.+)$/.exec(t['waitingPeriod'] ?? '');
      return carencia
        ? `Hecho del ${t['eventDate']} · carencia de ${carencia[1]} días desde el ${carencia[2]}`
        : evaluatedValue;
    }
    case 'REPORT_DEADLINE':
      return t['reportedAt']
        ? `Denunciado ${horas(t['reportedAt'])} después del hecho · máximo ${horas(t['max'])}`
        : evaluatedValue;
    case 'POLICE_DEADLINE':
      return t['policeReportAt']
        ? `Denuncia policial ${horas(t['policeReportAt'])} después del hecho · máximo ${horas(t['max'])}`
        : evaluatedValue;
    case 'MAX_EVENTS_YEAR': {
      const n = Number(t['events12m']);
      return t['events12m']
        ? `${n} ${n === 1 ? 'siniestro' : 'siniestros'} en los últimos 12 meses · máximo ${t['max']}`
        : evaluatedValue;
    }
    case 'POLICY_STANDING':
      return t['upToDate'] === 'true' ? 'La póliza está al día' : 'La póliza tiene saldo impago';
    case 'COVERAGE_EXCLUSION':
    case 'COVERAGE_INCLUSION':
      return t['claimCause']
        ? `Hecho generador: ${t['claimCause'].replace(/\s*\(id=\d+\)$/, '')}`
        : evaluatedValue;
    case 'COVERS_FAMILY_GROUP':
      // Solo se escribe fila cuando algún documento dijo quién fue el damnificado: si nadie lo
      // dijo la regla queda sin evaluar y no llega hasta acá.
      return t['affectedParty']
        ? `Damnificado: ${DAMNIFICADO[t['affectedParty']] ?? t['affectedParty']} · la cobertura no alcanza al grupo familiar`
        : evaluatedValue;
    case 'CLAIM_EXHAUSTS_COVERAGE': {
      const previos = Number(t['settledClaimsOnPolicy']);
      if (!t['settledClaimsOnPolicy'] || Number.isNaN(previos)) {
        return evaluatedValue;
      }
      return previos === 0
        ? 'Sin siniestros liquidados previos sobre esta póliza'
        : `${previos} ${previos === 1 ? 'siniestro liquidado previo' : 'siniestros liquidados previos'} sobre esta póliza · un siniestro agota la cobertura`;
    }
    default:
      // FRAUD_RECORD already comes as prose; an unknown type shows raw rather than hiding.
      return evaluatedValue.charAt(0).toUpperCase() + evaluatedValue.slice(1);
  }
}

/** `a=1 b=2 c` → {a: '1', b: '2 c'}: a value ends only at the next key. */
function tokens(raw: string): Record<string, string> {
  const found: Record<string, string> = {};
  for (const m of raw.matchAll(/(\w+)=(.*?)(?=\s+\w+=|$)/g)) {
    found[m[1]] = m[2].trim();
  }
  return found;
}

function horas(value: string | undefined): string {
  const n = /(\d+)/.exec(value ?? '');
  return n ? `${n[1]} h` : (value ?? '');
}

/** Mirror of PolicySnapshotResponse: the policy at classification time, not today's. */
export interface PolicySnapshot {
  externalPolicyNumber: string;
  sumInsured: number;
  inForce: boolean;
  paymentsUpToDate: boolean;
  previousClaims: number;
  /**
   * Lo que la compañía pagó por los siniestros previos del asegurado, en todas sus pólizas y
   * ramos — no lo que él reclamó, ni el saldo de esta cobertura. Por eso en pantalla va aparte de
   * la suma asegurada: restarlos no significa nada. Null en snapshots anteriores a la columna:
   * "Sin datos", nunca un cero.
   */
  totalAmountClaimed: number | null;
  queriedAt: string;
}
