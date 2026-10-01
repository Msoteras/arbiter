import { effectiveSimplifiedStatus, insuredMovementLabel, nextStepLabel } from './case-status';

// Tests the contract, not the wording: which movements the insured sees, and never the referral reason.
describe('movimientoAseguradoLabel', () => {
  it('tells the filing apart from the return with documents', () => {
    expect(insuredMovementLabel('PENDING_CLASSIFICATION', null)).toBe('Denuncia recibida');
    expect(insuredMovementLabel('PENDING_CLASSIFICATION', 'AWAITING_DOCUMENTATION')).toBe(
      'Recibimos tu documentación',
    );
    // Documents can also be uploaded while the case is under review.
    expect(insuredMovementLabel('PENDING_CLASSIFICATION', 'PENDING_ANALYST_REVIEW')).toBe(
      'Recibimos tu documentación',
    );
  });

  it('does not show the manual retry as a document upload', () => {
    expect(insuredMovementLabel('PENDING_CLASSIFICATION', 'CLASSIFICATION_FAILED')).toBeNull();
  });

  // Analyst-assignment rows are stored with from == to.
  it('does not show analyst assignments', () => {
    expect(insuredMovementLabel('PENDING_ANALYST_REVIEW', 'PENDING_ANALYST_REVIEW')).toBeNull();
    expect(insuredMovementLabel('CLASSIFICATION_FAILED', 'CLASSIFICATION_FAILED')).toBeNull();
    expect(insuredMovementLabel('AWAITING_DOCUMENTATION', 'AWAITING_DOCUMENTATION')).toBeNull();
  });

  it('tells them the case was referred to an expert', () => {
    expect(insuredMovementLabel('PENDING_EXPERT_REPORT', 'PENDING_ANALYST_REVIEW')).toBe(
      'Enviado a verificación con un perito',
    );
  });

  it('names the reopening of a closed case', () => {
    expect(insuredMovementLabel('PENDING_ANALYST_REVIEW', 'APPROVED')).toBe(
      'Reabrimos tu siniestro',
    );
    expect(insuredMovementLabel('PENDING_ANALYST_REVIEW', 'REJECTED')).toBe(
      'Reabrimos tu siniestro',
    );
    expect(insuredMovementLabel('PENDING_ANALYST_REVIEW', 'LAPSED')).toBe('Reabrimos tu siniestro');
    expect(insuredMovementLabel('PENDING_ANALYST_REVIEW', 'PENDING_CLASSIFICATION')).toBe(
      'Un analista está revisando tu caso',
    );
  });

  it('tells a return from the expert apart from a first review', () => {
    expect(insuredMovementLabel('PENDING_ANALYST_REVIEW', 'PENDING_EXPERT_REPORT')).toBe(
      'Verificación finalizada',
    );
    expect(insuredMovementLabel('PENDING_ANALYST_REVIEW', 'PENDING_CLASSIFICATION')).toBe(
      'Un analista está revisando tu caso',
    );
  });

  it('tells the trip to and back from the repair shop', () => {
    expect(insuredMovementLabel('PENDING_REPAIR', 'PENDING_ANALYST_REVIEW')).toBe(
      'Enviado al servicio técnico',
    );
    expect(insuredMovementLabel('PENDING_ANALYST_REVIEW', 'PENDING_REPAIR')).toBe(
      'Respuesta del servicio técnico recibida',
    );
  });

  it('does not show the classification failure', () => {
    expect(insuredMovementLabel('CLASSIFICATION_FAILED', 'PENDING_CLASSIFICATION')).toBeNull();
  });

  it('shows the resolution', () => {
    expect(insuredMovementLabel('APPROVED', 'PENDING_ANALYST_REVIEW')).toBe('Siniestro aprobado');
    expect(insuredMovementLabel('REJECTED', 'PENDING_ANALYST_REVIEW')).toBe('Siniestro rechazado');
  });

  it('no next step leaks the expert assessment or the classification', () => {
    const forbidden = ['perito', 'peritaje', 'informe', 'fraude', 'clasificac', 'riesgo', 'score'];
    const statuses: string[] = [
      'PENDING_CLASSIFICATION',
      'PENDING_ANALYST_REVIEW',
      'CLASSIFICATION_FAILED',
      'AWAITING_DOCUMENTATION',
      'PENDING_EXPERT_REPORT',
      'PENDING_REPAIR',
      'APPROVED',
      'REJECTED',
      'LAPSED',
    ];

    for (const status of statuses) {
      const text = nextStepLabel(status).toLowerCase();
      expect(text.length).toBeGreaterThan(0);
      for (const word of forbidden) {
        expect(text).not.toContain(word);
      }
    }
  });

  it('no label mentions fraud, classification or risk', () => {
    const forbidden = ['fraude', 'fraud', 'llm', 'riesgo', 'score', 'sospech', 'clasificac'];
    const statuses = [
      'PENDING_CLASSIFICATION',
      'AWAITING_DOCUMENTATION',
      'PENDING_ANALYST_REVIEW',
      'PENDING_EXPERT_REPORT',
      'PENDING_REPAIR',
      'APPROVED',
      'REJECTED',
      'CLASSIFICATION_FAILED',
      'LAPSED',
    ];
    const fromStatuses = [null, ...statuses];

    for (const to of statuses) {
      for (const from of fromStatuses) {
        const label = (insuredMovementLabel(to, from) ?? '').toLowerCase();
        for (const word of forbidden) {
          expect(label).withContext(`${from} → ${to}`).not.toContain(word);
        }
      }
    }
  });
});

describe('effectiveSimplifiedStatus', () => {
  it('does not step back when the insured uploads documents', () => {
    expect(
      effectiveSimplifiedStatus('PENDING_CLASSIFICATION', [
        'PENDING_CLASSIFICATION',
        'AWAITING_DOCUMENTATION',
      ]),
    ).toBe('EN_TRAMITE');
  });

  it('marks Terminado only while the case is closed', () => {
    expect(effectiveSimplifiedStatus('APPROVED', ['PENDING_ANALYST_REVIEW'])).toBe('TERMINADO');
    expect(effectiveSimplifiedStatus('LAPSED', ['AWAITING_DOCUMENTATION'])).toBe('TERMINADO');
  });

  it('goes back to En trámite when a closed case reopens', () => {
    expect(
      effectiveSimplifiedStatus('PENDING_ANALYST_REVIEW', ['PENDING_ANALYST_REVIEW', 'REJECTED']),
    ).toBe('EN_TRAMITE');
    expect(
      effectiveSimplifiedStatus('PENDING_ANALYST_REVIEW', ['AWAITING_DOCUMENTATION', 'LAPSED']),
    ).toBe('EN_TRAMITE');
  });
});
