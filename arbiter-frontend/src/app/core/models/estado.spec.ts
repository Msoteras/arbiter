import { estadoSimplificadoEfectivo, movimientoAseguradoLabel } from './estado';

/**
 * El seguimiento del asegurado lista los movimientos del expediente, y ese listado sale de acá.
 * Lo que se prueba no es la redacción sino el contrato: qué movimientos ve, cuáles no, y que la
 * derivación se le cuente SIN el motivo — el motivo es la sospecha, y todavía no hay nada probado.
 */
describe('movimientoAseguradoLabel', () => {
  it('distingue el alta de la vuelta con documentación', () => {
    // fromStatus null = fila de creación del expediente.
    expect(movimientoAseguradoLabel('PENDING_CLASSIFICATION', null)).toBe('Denuncia recibida');
    expect(movimientoAseguradoLabel('PENDING_CLASSIFICATION', 'AWAITING_DOCUMENTATION'))
      .toBe('Recibimos tu documentación');
    // También se puede cargar documentación con el expediente ya en revisión.
    expect(movimientoAseguradoLabel('PENDING_CLASSIFICATION', 'PENDING_ANALYST_REVIEW'))
      .toBe('Recibimos tu documentación');
  });

  /**
   * El reintento manual del analista sobre un expediente que falló vuelve a PENDING_CLASSIFICATION
   * sin que el asegurado haya mandado nada. Se le mostraba "Recibimos tu documentación", que le
   * inventaba una carga que nunca hizo (lo pescó la usuaria en el siniestro 15 de Provincia).
   */
  it('no muestra el reintento manual como una carga de documentación', () => {
    expect(movimientoAseguradoLabel('PENDING_CLASSIFICATION', 'CLASSIFICATION_FAILED')).toBeNull();
  });

  /**
   * Las filas de asignación de analista se guardan con from == to. No son un movimiento del
   * expediente: sin esto, cada reasignación repetía "un analista está revisando tu caso".
   */
  it('no muestra las asignaciones de analista', () => {
    expect(movimientoAseguradoLabel('PENDING_ANALYST_REVIEW', 'PENDING_ANALYST_REVIEW')).toBeNull();
    expect(movimientoAseguradoLabel('CLASSIFICATION_FAILED', 'CLASSIFICATION_FAILED')).toBeNull();
    expect(movimientoAseguradoLabel('AWAITING_DOCUMENTATION', 'AWAITING_DOCUMENTATION')).toBeNull();
  });

  it('le cuenta que el caso se derivó a un perito', () => {
    expect(movimientoAseguradoLabel('PENDING_EXPERT_REPORT', 'PENDING_ANALYST_REVIEW'))
      .toBe('Enviado a verificación con un perito');
  });

  /**
   * Una reapertura vuelve al mismo PENDING_ANALYST_REVIEW que una clasificación normal, pero para
   * el asegurado no es lo mismo: ya recibió el mail de la resolución anterior. El motivo de la
   * reapertura no se le cuenta nunca — es interno.
   */
  it('nombra la reapertura de un expediente cerrado', () => {
    expect(movimientoAseguradoLabel('PENDING_ANALYST_REVIEW', 'APPROVED'))
      .toBe('Reabrimos tu siniestro');
    expect(movimientoAseguradoLabel('PENDING_ANALYST_REVIEW', 'REJECTED'))
      .toBe('Reabrimos tu siniestro');
    expect(movimientoAseguradoLabel('PENDING_ANALYST_REVIEW', 'LAPSED'))
      .toBe('Reabrimos tu siniestro');
    // Y la clasificación normal sigue siendo la de siempre.
    expect(movimientoAseguradoLabel('PENDING_ANALYST_REVIEW', 'PENDING_CLASSIFICATION'))
      .toBe('Un analista está revisando tu caso');
  });

  it('distingue volver del peritaje de entrar a revisión por primera vez', () => {
    expect(movimientoAseguradoLabel('PENDING_ANALYST_REVIEW', 'PENDING_EXPERT_REPORT'))
      .toBe('Verificación finalizada');
    expect(movimientoAseguradoLabel('PENDING_ANALYST_REVIEW', 'PENDING_CLASSIFICATION'))
      .toBe('Un analista está revisando tu caso');
  });

  /** Una falla técnica del clasificador no le pide nada ni cambia nada de su lado. */
  it('no muestra la falla de clasificación', () => {
    expect(movimientoAseguradoLabel('CLASSIFICATION_FAILED', 'PENDING_CLASSIFICATION')).toBeNull();
  });

  it('muestra la resolución', () => {
    expect(movimientoAseguradoLabel('APPROVED', 'PENDING_ANALYST_REVIEW')).toBe('Siniestro aprobado');
    expect(movimientoAseguradoLabel('REJECTED', 'PENDING_ANALYST_REVIEW')).toBe('Siniestro rechazado');
  });

  /**
   * La red de seguridad de todo esto: ninguna etiqueta puede filtrar la clasificación del modelo,
   * el veredicto del peritaje ni el nivel de riesgo. Se mapea el ESTADO y nunca el `reason` del
   * historial, que trae textos como "informe de peritaje recibido: FRAUD_CONFIRMED".
   */
  it('ninguna etiqueta menciona fraude, clasificación ni riesgo', () => {
    const prohibidas = ['fraude', 'fraud', 'llm', 'riesgo', 'score', 'sospech', 'clasificac'];
    const estados = [
      'PENDING_CLASSIFICATION',
      'AWAITING_DOCUMENTATION',
      'PENDING_ANALYST_REVIEW',
      'PENDING_EXPERT_REPORT',
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

/**
 * El progreso que ve el asegurado es monótono para que el stepper no vuelva al día 1 cuando el
 * estado técnico retrocede dentro del trámite. Pero "Terminado" tiene que seguir significando
 * "terminado ahora": una reapertura es un retroceso real.
 */
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
    // Reapertura: el expediente pasó por un terminal pero hoy está de nuevo en revisión. Sin el
    // techo, el máximo histórico lo dejaba clavado en "Terminado" sobre un caso reabierto.
    expect(
      estadoSimplificadoEfectivo('PENDING_ANALYST_REVIEW', ['PENDING_ANALYST_REVIEW', 'REJECTED']),
    ).toBe('EN_TRAMITE');
    expect(
      estadoSimplificadoEfectivo('PENDING_ANALYST_REVIEW', ['AWAITING_DOCUMENTATION', 'LAPSED']),
    ).toBe('EN_TRAMITE');
  });
});
