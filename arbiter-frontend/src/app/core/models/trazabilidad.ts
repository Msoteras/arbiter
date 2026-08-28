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
  FRAUD_RECORD: 'Antecedente de fraude',
  // The engine never writes it (the gate leaves no rule_result), but the demo seed does.
  FAST_TRACK: 'Criterio de Fast Track',
};

export function ruleTypeLabel(ruleType: string): string {
  return RULE_TYPE_LABELS[ruleType] ?? ruleType;
}

// The engine writes PASS/FAIL; the demo seed wrote CUMPLE/NO_CUMPLE, and those rows are live.
// Anything else is shown verbatim and without a tone: calling an unknown literal a failure would
// accuse a rule that may well have passed.
const PASSED = ['PASS', 'CUMPLE'];
const FAILED = ['FAIL', 'NO_CUMPLE'];

export function ruleResultLabel(result: string): string {
  if (PASSED.includes(result)) return 'Cumple';
  return FAILED.includes(result) ? 'No cumple' : result;
}

export function ruleResultTone(result: string): StatusTone {
  if (PASSED.includes(result)) return 'ok';
  return FAILED.includes(result) ? 'danger' : 'neutral';
}

/**
 * evaluated_value arrives as the engine writes it, in key=value pairs. That literal is the audit
 * record and stays untouched in the DB; the analyst gets the sentence. Same numbers, nothing is
 * recomputed here.
 */
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
  /** Null on snapshots older than the column — "Sin datos", never a zero. */
  totalAmountClaimed: number | null;
  queriedAt: string;
}
