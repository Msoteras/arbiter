import { estadoSimplificadoEfectivo, movimientoAseguradoLabel, proximoPaso } from './estado';

// Tests the contract, not the wording: which movements the insured sees, and never the referral reason.
describe('movimientoAseguradoLabel', () => {
  it('distingue el alta de la vuelta con documentación', () => {
    expect(movimientoAseguradoLabel('PENDING_CLASSIFICATION', null)).toBe('Denuncia recibida');
    expect(movimientoAseguradoLabel('PENDING_CLASSIFICATION', 'AWAITING_DOCUMENTATION')).toBe(
      'Recibimos tu documentación',
    );
    // Documents can also be uploaded while the case is under review.
    expect(movimientoAseguradoLabel('PENDING_CLASSIFICATION', 'PENDING_ANALYST_REVIEW')).toBe(
      'Recibimos tu documentación',
    );
  });

  it('no muestra el reintento manual como una carga de documentación', () => {
    expect(movimientoAseguradoLabel('PENDING_CLASSIFICATION', 'CLASSIFICATION_FAILED')).toBeNull();
  });

  // Analyst-assignment rows are stored with from == to.
  it('no muestra las asignaciones de analista', () => {
    expect(movimientoAseguradoLabel('PENDING_ANALYST_REVIEW', 'PENDING_ANALYST_REVIEW')).toBeNull();
    expect(movimientoAseguradoLabel('CLASSIFICATION_FAILED', 'CLASSIFICATION_FAILED')).toBeNull();
    expect(movimientoAseguradoLabel('AWAITING_DOCUMENTATION', 'AWAITING_DOCUMENTATION')).toBeNull();
  });

  it('le cuenta que el caso se derivó a un perito', () => {
    expect(movimientoAseguradoLabel('PENDING_EXPERT_REPORT', 'PENDING_ANALYST_REVIEW')).toBe(
      'Enviado a verificación con un perito',
    );
  });

  it('nombra la reapertura de un expediente cerrado', () => {
    expect(movimientoAseguradoLabel('PENDING_ANALYST_REVIEW', 'APPROVED')).toBe(
      'Reabrimos tu siniestro',
    );
    expect(movimientoAseguradoLabel('PENDING_ANALYST_REVIEW', 'REJECTED')).toBe(
      'Reabrimos tu siniestro',
    );
    expect(movimientoAseguradoLabel('PENDING_ANALYST_REVIEW', 'LAPSED')).toBe(
      'Reabrimos tu siniestro',
    );
    expect(movimientoAseguradoLabel('PENDING_ANALYST_REVIEW', 'PENDING_CLASSIFICATION')).toBe(
      'Un analista está revisando tu caso',
    );
  });

  it('distingue volver del peritaje de entrar a revisión por primera vez', () => {
    expect(movimientoAseguradoLabel('PENDING_ANALYST_REVIEW', 'PENDING_EXPERT_REPORT')).toBe(
      'Verificación finalizada',
    );
    expect(movimientoAseguradoLabel('PENDING_ANALYST_REVIEW', 'PENDING_CLASSIFICATION')).toBe(
      'Un analista está revisando tu caso',
    );
  });

  it('cuenta la ida y la vuelta del servicio técnico', () => {
    expect(movimientoAseguradoLabel('PENDING_REPAIR', 'PENDING_ANALYST_REVIEW')).toBe(
      'Enviado al servicio técnico',
    );
    expect(movimientoAseguradoLabel('PENDING_ANALYST_REVIEW', 'PENDING_REPAIR')).toBe(
      'Respuesta del servicio técnico recibida',
    );
  });

  it('no muestra la falla de clasificación', () => {
    expect(movimientoAseguradoLabel('CLASSIFICATION_FAILED', 'PENDING_CLASSIFICATION')).toBeNull();
  });

  it('muestra la resolución', () => {
    expect(movimientoAseguradoLabel('APPROVED', 'PENDING_ANALYST_REVIEW')).toBe(
      'Siniestro aprobado',
    );
    expect(movimientoAseguradoLabel('REJECTED', 'PENDING_ANALYST_REVIEW')).toBe(
      'Siniestro rechazado',
    );
  });

  it('ningún próximo paso filtra el peritaje ni la clasificación', () => {
    const prohibidas = ['perito', 'peritaje', 'informe', 'fraude', 'clasificac', 'riesgo', 'score'];
    const estados: string[] = [
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

    for (const estado of estados) {
      const texto = proximoPaso(estado).toLowerCase();
      expect(texto.length).toBeGreaterThan(0);
      for (const palabra of prohibidas) {
        expect(texto).not.toContain(palabra);
      }
    }
  });

  it('ninguna etiqueta menciona fraude, clasificación ni riesgo', () => {
    const prohibidas = ['fraude', 'fraud', 'llm', 'riesgo', 'score', 'sospech', 'clasificac'];
    const estados = [
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
    const desde = [null, ...estados];

    for (const to of estados) {
      for (const from of desde) {
        const label = (movimientoAseguradoLabel(to, from) ?? '').toLowerCase();
        for (const palabra of prohibidas) {
          expect(label).withContext(`${from} → ${to}`).not.toContain(palabra);
        }
      }
    }
  });
});

describe('estadoSimplificadoEfectivo', () => {
  it('no retrocede cuando el asegurado sube documentación', () => {
    expect(
      estadoSimplificadoEfectivo('PENDING_CLASSIFICATION', [
        'PENDING_CLASSIFICATION',
        'AWAITING_DOCUMENTATION',
      ]),
    ).toBe('EN_TRAMITE');
  });

  it('marca Terminado solo mientras el expediente está cerrado', () => {
    expect(estadoSimplificadoEfectivo('APPROVED', ['PENDING_ANALYST_REVIEW'])).toBe('TERMINADO');
    expect(estadoSimplificadoEfectivo('LAPSED', ['AWAITING_DOCUMENTATION'])).toBe('TERMINADO');
  });

  it('vuelve a En trámite cuando un expediente cerrado se reabre', () => {
    expect(
      estadoSimplificadoEfectivo('PENDING_ANALYST_REVIEW', ['PENDING_ANALYST_REVIEW', 'REJECTED']),
    ).toBe('EN_TRAMITE');
    expect(
      estadoSimplificadoEfectivo('PENDING_ANALYST_REVIEW', ['AWAITING_DOCUMENTATION', 'LAPSED']),
    ).toBe('EN_TRAMITE');
  });
});
