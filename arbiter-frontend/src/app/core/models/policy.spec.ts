import {
  Policy,
  PolicyValidity,
  isExpired,
  policyPaymentLabel,
  policyPaymentTone,
  policyValidityLabel,
  policyValidityTone,
} from './policy';

function policy(overrides: Partial<Policy> = {}): Policy {
  return {
    policyNumber: 'POL-CEL-2026-042',
    insurerId: '1',
    insurerName: 'BBVA Seguros Argentina S.A.',
    insuredName: 'Martina Soteras',
    insuredId: '42.987.654',
    contactEmail: null,
    contactPhone: null,
    branch: 'Celulares',
    insuredItem: 'Samsung Galaxy A56',
    product: 'Celular Protegido Premium',
    effectiveFrom: '2026-01-01T00:00:00',
    effectiveTo: '2027-01-01T23:59:59',
    validity: 'CURRENT',
    upToDate: true,
    insuredAmount: 1300000,
    deductible: 130000,
    coverages: [],
    ...overrides,
  };
}

describe('policy', () => {
  describe('validity', () => {
    it('comes from the field the backend sends, not from the dates', () => {
      // Dates look current, but the backend field wins.
      const expired = policy({
        effectiveFrom: '2026-01-01T00:00:00',
        effectiveTo: '2027-01-01T23:59:59',
        validity: 'EXPIRED',
      });

      expect(isExpired(expired)).toBeTrue();
      expect(policyValidityLabel(expired)).toBe('Vencida');
      expect(policyValidityTone(expired)).toBe('danger');
    });

    it('maps each status to its label and tone', () => {
      const cases: [PolicyValidity, string, string][] = [
        ['CURRENT', 'Vigente', 'ok'],
        ['EXPIRED', 'Vencida', 'danger'],
        ['NOT_YET_ACTIVE', 'Aún no vigente', 'info'],
      ];

      for (const [validity, label, tone] of cases) {
        const p = policy({ validity });
        expect(policyValidityLabel(p)).toBe(label);
        expect(policyValidityTone(p)).toBe(tone);
      }
    });

    it('a policy that has not started yet is not expired', () => {
      expect(isExpired(policy({ validity: 'NOT_YET_ACTIVE' }))).toBeFalse();
    });
  });

  describe('payment status', () => {
    it('is independent from the validity', () => {
      const currentWithDebt = policy({ validity: 'CURRENT', upToDate: false });
      expect(isExpired(currentWithDebt)).toBeFalse();
      expect(policyPaymentLabel(currentWithDebt)).toBe('Con deuda');
      expect(policyPaymentTone(currentWithDebt)).toBe('warning');

      const paidUp = policy({ upToDate: true });
      expect(policyPaymentLabel(paidUp)).toBe('Al día');
      expect(policyPaymentTone(paidUp)).toBe('ok');
    });
  });
});
