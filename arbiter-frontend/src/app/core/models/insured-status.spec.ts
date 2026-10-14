import {
  InsuredMovement,
  InsuredStatusCode,
  insuredMovementLabel,
  insuredNextStep,
  insuredStatusDescription,
  insuredStatusTitle,
  insuredStatusTone,
  isFinalInsuredStatus,
} from './insured-status';

const CODES: InsuredStatusCode[] = [
  'UNDER_ANALYSIS',
  'AWAITING_DOCUMENTATION',
  'UNDER_VERIFICATION',
  'UNDER_REPAIR',
  'APPROVED',
  'REJECTED',
  'LAPSED',
  'CLOSED',
];

function movement(
  to: InsuredStatusCode,
  from: InsuredStatusCode | null,
  actor: string | null = null,
): InsuredMovement {
  return {
    fromInsuredStatus: from ? { code: from, label: from } : null,
    toInsuredStatus: { code: to, label: to },
    actor,
    changedAt: '2026-10-09T12:00:00Z',
  };
}

// Tests the contract, not the wording: which movements the insured sees, and never the referral reason.
describe('insuredMovementLabel', () => {
  it('tells the filing apart from the return with documents', () => {
    expect(insuredMovementLabel(movement('UNDER_ANALYSIS', null, 'INSURED'))).toBe(
      'Denuncia recibida',
    );
    expect(
      insuredMovementLabel(movement('UNDER_ANALYSIS', 'AWAITING_DOCUMENTATION', 'INSURED')),
    ).toBe('Recibimos tu documentación');
    // Documents can also be uploaded while the case is under review.
    expect(insuredMovementLabel(movement('UNDER_ANALYSIS', 'UNDER_ANALYSIS', 'INSURED'))).toBe(
      'Recibimos tu documentación',
    );
  });

  it('tells them the case was referred to an expert, and when it came back', () => {
    expect(insuredMovementLabel(movement('UNDER_VERIFICATION', 'UNDER_ANALYSIS'))).toBe(
      'Enviado a verificación con un perito',
    );
    expect(insuredMovementLabel(movement('UNDER_ANALYSIS', 'UNDER_VERIFICATION'))).toBe(
      'Verificación finalizada',
    );
  });

  it('tells the trip to and back from the repair shop', () => {
    expect(insuredMovementLabel(movement('UNDER_REPAIR', 'UNDER_ANALYSIS'))).toBe(
      'Enviado al servicio técnico',
    );
    expect(insuredMovementLabel(movement('UNDER_ANALYSIS', 'UNDER_REPAIR'))).toBe(
      'Respuesta del servicio técnico recibida',
    );
  });

  it('names the reopening of a closed case', () => {
    for (const closed of ['APPROVED', 'REJECTED', 'LAPSED'] as const) {
      expect(insuredMovementLabel(movement('UNDER_ANALYSIS', closed))).toBe(
        'Reabrimos tu siniestro',
      );
    }
  });

  it('shows the resolution', () => {
    expect(insuredMovementLabel(movement('APPROVED', 'UNDER_ANALYSIS'))).toBe('Siniestro aprobado');
    expect(insuredMovementLabel(movement('REJECTED', 'UNDER_ANALYSIS'))).toBe(
      'Siniestro rechazado',
    );
  });

  it('no label mentions fraud, classification or risk', () => {
    const forbidden = ['fraude', 'fraud', 'llm', 'riesgo', 'score', 'sospech', 'clasificac'];

    for (const to of CODES) {
      for (const from of [null, ...CODES]) {
        for (const actor of [null, 'INSURED']) {
          const label = insuredMovementLabel(movement(to, from, actor)).toLowerCase();
          for (const word of forbidden) {
            expect(label).withContext(`${from} → ${to}`).not.toContain(word);
          }
        }
      }
    }
  });
});

describe('insuredNextStep', () => {
  it('never leaks the expert assessment or the classification', () => {
    const forbidden = ['perito', 'peritaje', 'informe', 'fraude', 'clasificac', 'riesgo', 'score'];

    for (const code of CODES) {
      const text = insuredNextStep(code).toLowerCase();
      expect(text.length).toBeGreaterThan(0);
      for (const word of forbidden) {
        expect(text).not.toContain(word);
      }
    }
  });
});

describe('insuredStatusTitle', () => {
  const filing = movement('UNDER_ANALYSIS', null, 'INSURED');

  it('acknowledges the documents the insured just sent', () => {
    const history = [filing, movement('UNDER_ANALYSIS', 'AWAITING_DOCUMENTATION', 'INSURED')];

    expect(insuredStatusTitle('UNDER_ANALYSIS', history)).toBe('Recibimos tu documentación');
    expect(insuredStatusDescription('UNDER_ANALYSIS', history)).toContain('reevaluando');
  });

  it('does not take the filing for a document upload', () => {
    expect(insuredStatusTitle('UNDER_ANALYSIS', [filing])).toBe('Tu siniestro está en análisis');
  });

  it('drops the acknowledgement once the case moves on', () => {
    const history = [
      filing,
      movement('UNDER_ANALYSIS', 'AWAITING_DOCUMENTATION', 'INSURED'),
      movement('APPROVED', 'UNDER_ANALYSIS'),
    ];

    expect(insuredStatusTitle('APPROVED', history)).toBe('Tu siniestro fue aprobado');
  });

  it('tells the insured a closed claim ended, without the internal reason', () => {
    expect(isFinalInsuredStatus('CLOSED')).toBeTrue();
    expect(insuredStatusTone('CLOSED')).toBe('neutral');
    expect(insuredStatusTitle('CLOSED')).toBe('Cerramos tu denuncia');
    expect(
      insuredMovementLabel({
        fromInsuredStatus: { code: 'UNDER_ANALYSIS', label: 'En análisis' },
        toInsuredStatus: { code: 'CLOSED', label: 'Cerrado' },
        actor: null,
        changedAt: '2026-10-14T12:00:00Z',
      }),
    ).toBe('Denuncia cerrada');
  });
});
