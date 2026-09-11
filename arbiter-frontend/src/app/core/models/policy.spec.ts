import {
  Policy,
  policyPaymentLabel,
  policyPaymentTone,
  policyValidity,
  policyValidityLabel,
  policyValidityTone,
} from './policy';

const NOW = new Date('2026-09-10T12:00:00');

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
    upToDate: true,
    insuredAmount: 1300000,
    deductible: 130000,
    coverages: [],
    ...overrides,
  };
}

describe('policy', () => {
  describe('policyValidity', () => {
    it('es vigente dentro del período de cobertura', () => {
      expect(policyValidity(policy(), NOW)).toBe('vigente');
    });

    it('es vencida cuando la vigencia ya terminó', () => {
      const vencida = policy({
        effectiveFrom: '2025-01-01T00:00:00',
        effectiveTo: '2026-01-01T23:59:59',
      });
      expect(policyValidity(vencida, NOW)).toBe('vencida');
    });

    it('es pendiente cuando la vigencia todavía no arrancó', () => {
      const futura = policy({
        effectiveFrom: '2026-12-01T00:00:00',
        effectiveTo: '2027-12-01T23:59:59',
      });
      expect(policyValidity(futura, NOW)).toBe('pendiente');
    });

    /**
     * La vigencia lleva hora: una póliza que vence hoy a las 08:00 ya no cubre a las 12:00, aunque
     * siga siendo "hoy". Comparar solo por fecha la dejaba vigente media jornada de más.
     */
    it('respeta la hora del fin de vigencia, no solo el día', () => {
      const venceHoyTemprano = policy({ effectiveTo: '2026-09-10T08:00:00' });
      expect(policyValidity(venceHoyTemprano, NOW)).toBe('vencida');

      const venceHoyMasTarde = policy({ effectiveTo: '2026-09-10T23:59:59' });
      expect(policyValidity(venceHoyMasTarde, NOW)).toBe('vigente');
    });
  });

  describe('labels y tonos de vigencia', () => {
    it('mapea cada estado a su label y su tono', () => {
      const vigente = policy();
      expect(policyValidityLabel(vigente, NOW)).toBe('Vigente');
      expect(policyValidityTone(vigente, NOW)).toBe('ok');

      const vencida = policy({ effectiveTo: '2026-01-01T23:59:59' });
      expect(policyValidityLabel(vencida, NOW)).toBe('Vencida');
      expect(policyValidityTone(vencida, NOW)).toBe('danger');

      const pendiente = policy({
        effectiveFrom: '2026-12-01T00:00:00',
        effectiveTo: '2027-12-01T00:00:00',
      });
      expect(policyValidityLabel(pendiente, NOW)).toBe('Aún no vigente');
      expect(policyValidityTone(pendiente, NOW)).toBe('info');
    });
  });

  describe('estado de pago', () => {
    it('es un eje independiente de la vigencia', () => {
      // Vigente y con deuda: el cruce que se pierde si se colapsan en un solo semáforo.
      const vigenteConDeuda = policy({ upToDate: false });
      expect(policyValidity(vigenteConDeuda, NOW)).toBe('vigente');
      expect(policyPaymentLabel(vigenteConDeuda)).toBe('Con deuda');
      expect(policyPaymentTone(vigenteConDeuda)).toBe('warning');

      const alDia = policy({ upToDate: true });
      expect(policyPaymentLabel(alDia)).toBe('Al día');
      expect(policyPaymentTone(alDia)).toBe('ok');
    });
  });
});
