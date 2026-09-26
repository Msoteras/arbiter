import { StatusTone } from './status-tone';

/** A policy has several coverages; the backend picks the one matching the reported claim cause. */
export interface PolicyCoverage {
  code: string;
  description: string;
  insuredAmount: number;
  /** Absolute amount. */
  deductible: number;
  /** Same deductible in percentage points (10 = 10%). */
  deductiblePct: number | null;
}

// Mirrors cases-service's PolicyResponse (GET /api/v1/policies).
export interface Policy {
  policyNumber: string;
  insurerId: string;
  insurerName: string;
  insuredName: string;
  insuredId: string;
  contactEmail: string | null;
  contactPhone: string | null;
  branch: string;
  insuredItem: string | null;
  product: string;
  /** ISO with time and no timezone, for DISPLAY only: whether it covers is `validity`. */
  effectiveFrom: string;
  effectiveTo: string;
  validity: PolicyValidity;
  upToDate: boolean;
  /** The FIRST coverage's amounts, for the card summary only; decisions use `coverages`. */
  insuredAmount: number;
  deductible: number;
  coverages: PolicyCoverage[];
}

// Validity and payment status are independent axes: a policy can be in force with debt, or paid
// up and expired. Keep them as separate indicators.

/** From the backend, never derived from the timezone-less dates. `NOT_YET_ACTIVE`: future start. */
export type PolicyValidity = 'CURRENT' | 'NOT_YET_ACTIVE' | 'EXPIRED';

export function isExpired(policy: Policy): boolean {
  return policy.validity === 'EXPIRED';
}

const VALIDITY_LABELS: Record<PolicyValidity, string> = {
  CURRENT: 'Vigente',
  EXPIRED: 'Vencida',
  NOT_YET_ACTIVE: 'Aún no vigente',
};

const VALIDITY_TONES: Record<PolicyValidity, StatusTone> = {
  CURRENT: 'ok',
  EXPIRED: 'danger',
  NOT_YET_ACTIVE: 'info',
};

export function policyValidityLabel(policy: Policy): string {
  return VALIDITY_LABELS[policy.validity];
}

export function policyValidityTone(policy: Policy): StatusTone {
  return VALIDITY_TONES[policy.validity];
}

/** `upToDate` is PAYMENT status, not validity. */
export function policyPaymentLabel(policy: Policy): string {
  return policy.upToDate ? 'Al día' : 'Con deuda';
}

// Warning, not danger: arrears alone do not void the policy; the rules engine decides that.
export function policyPaymentTone(policy: Policy): StatusTone {
  return policy.upToDate ? 'ok' : 'warning';
}
