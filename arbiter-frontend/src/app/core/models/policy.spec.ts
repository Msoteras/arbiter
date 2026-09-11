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
  /**
   * La vigencia llega calculada del backend y no se deriva de las fechas: vienen sin zona horaria,
   * y comparándolas acá el navegador (hora argentina) contradecía al backend (UTC) durante las
   * tres horas previas a la medianoche del día del vencimiento.
   */
  describe('vigencia', () => {
    it('sale del campo que manda el backend, no de las fechas', () => {
      // Fechas que "parecen" vigentes, pero el backend ya la dio por vencida: manda el campo.
      const vencida = policy({
        effectiveFrom: '2026-01-01T00:00:00',
        effectiveTo: '2027-01-01T23:59:59',
        validity: 'EXPIRED',
      });

      expect(isExpired(vencida)).toBeTrue();
      expect(policyValidityLabel(vencida)).toBe('Vencida');
      expect(policyValidityTone(vencida)).toBe('danger');
    });

    it('mapea cada estado a su label y su tono', () => {
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

    /** Solo la vencida se pliega en "Mis pólizas": la que todavía no arrancó sigue arriba. */
    it('una póliza que aún no arrancó no cuenta como vencida', () => {
      expect(isExpired(policy({ validity: 'NOT_YET_ACTIVE' }))).toBeFalse();
    });
  });

  describe('estado de pago', () => {
    it('es un eje independiente de la vigencia', () => {
      // Vigente y con deuda: el cruce que se pierde si se colapsan en un solo semáforo.
      const vigenteConDeuda = policy({ validity: 'CURRENT', upToDate: false });
      expect(isExpired(vigenteConDeuda)).toBeFalse();
      expect(policyPaymentLabel(vigenteConDeuda)).toBe('Con deuda');
      expect(policyPaymentTone(vigenteConDeuda)).toBe('warning');

      const alDia = policy({ upToDate: true });
      expect(policyPaymentLabel(alDia)).toBe('Al día');
      expect(policyPaymentTone(alDia)).toBe('ok');
    });
  });
});
