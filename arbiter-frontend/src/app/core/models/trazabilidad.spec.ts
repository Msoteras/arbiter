import {
  isFastTrackCriterion,
  ruleEvaluationText,
  ruleResultLabel,
  ruleResultTone,
  ruleTypeLabel,
} from './trazabilidad';

describe('trazabilidad', () => {
  describe('ruleResultTone / ruleResultLabel', () => {
    it('traduce el vocabulario del motor', () => {
      expect(ruleResultTone('PASS')).toBe('ok');
      expect(ruleResultTone('FAIL')).toBe('danger');

      expect(ruleResultLabel('PASS')).toBe('Cumple');
      expect(ruleResultLabel('FAIL')).toBe('No cumple');
    });

    // Los CUMPLE/NO_CUMPLE del seed viejo ya no existen: se migraron en los datos. Si alguna base
    // quedó sin migrar, caen acá — se ven crudos y sin tono, que es degradar prolijo y no mentir.
    it('trata el vocabulario viejo como cualquier literal desconocido', () => {
      expect(ruleResultTone('CUMPLE')).toBe('neutral');
      expect(ruleResultLabel('CUMPLE')).toBe('CUMPLE');
    });

    // El bug: todo lo que no era PASS se pintaba como fallo, y un Fast Track que había cumplido
    // salía en rojo diciendo "No cumple".
    it('no da por fallada una regla con un literal que no reconoce', () => {
      expect(ruleResultTone('LO_QUE_SEA')).toBe('neutral');
      expect(ruleResultLabel('LO_QUE_SEA')).toBe('LO_QUE_SEA');
    });
  });

  // Las dos reglas de alcance de cobertura (D9). Se configuran en la cobertura y no en la solapa
  // de reglas duras, así que no heredan label de ahí: el suyo vive en este archivo.
  describe('reglas de alcance de cobertura', () => {
    it('les pone nombre en castellano', () => {
      expect(ruleTypeLabel('COVERS_FAMILY_GROUP')).toBe('Alcance al grupo familiar');
      expect(ruleTypeLabel('CLAIM_EXHAUSTS_COVERAGE')).toBe(
        'Cobertura consumida por un siniestro previo',
      );
    });

    it('dice quién fue el damnificado en castellano', () => {
      expect(ruleEvaluationText('COVERS_FAMILY_GROUP', 'affectedParty=FAMILIAR')).toBe(
        'Damnificado: un familiar · la cobertura no alcanza al grupo familiar',
      );
      expect(ruleEvaluationText('COVERS_FAMILY_GROUP', 'affectedParty=TITULAR')).toBe(
        'Damnificado: el titular · la cobertura no alcanza al grupo familiar',
      );
    });

    it('distingue el cero de la ausencia de dato al contar siniestros liquidados', () => {
      expect(ruleEvaluationText('CLAIM_EXHAUSTS_COVERAGE', 'settledClaimsOnPolicy=0 max=0')).toBe(
        'Sin siniestros liquidados previos sobre esta póliza',
      );
      expect(ruleEvaluationText('CLAIM_EXHAUSTS_COVERAGE', 'settledClaimsOnPolicy=1 max=0')).toBe(
        '1 siniestro liquidado previo sobre esta póliza · un siniestro agota la cobertura',
      );
      expect(ruleEvaluationText('CLAIM_EXHAUSTS_COVERAGE', 'settledClaimsOnPolicy=2 max=0')).toBe(
        '2 siniestros liquidados previos sobre esta póliza · un siniestro agota la cobertura',
      );
    });
  });

  describe('ruleEvaluationText', () => {
    it('arma la frase de cada tipo de regla con los mismos números del literal', () => {
      expect(
        ruleEvaluationText(
          'POLICY_IN_FORCE',
          'eventDate=20/08/2026 20:00 coverageWindow=01/01/2026 00:00..01/01/2027 23:59',
        ),
      ).toBe('Hecho del 20/08/2026 20:00 · vigencia del 01/01/2026 00:00 al 01/01/2027 23:59');

      expect(
        ruleEvaluationText(
          'WAITING_PERIOD',
          'eventDate=20/08/2026 20:00 waitingPeriod=30d from 01/01/2026 00:00',
        ),
      ).toBe('Hecho del 20/08/2026 20:00 · carencia de 30 días desde el 01/01/2026 00:00');

      expect(ruleEvaluationText('REPORT_DEADLINE', 'reportedAt=+20h max=72h')).toBe(
        'Denunciado 20 h después del hecho · máximo 72 h',
      );

      expect(ruleEvaluationText('POLICE_DEADLINE', 'policeReportAt=+0h max=72h')).toBe(
        'Denuncia policial 0 h después del hecho · máximo 72 h',
      );

      expect(ruleEvaluationText('POLICY_STANDING', 'upToDate=false')).toBe(
        'La póliza tiene saldo impago',
      );

      expect(ruleEvaluationText('COVERAGE_INCLUSION', 'claimCause=Hurto (id=3)')).toBe(
        'Hecho generador: Hurto',
      );
    });

    it('singulariza el tope de eventos', () => {
      expect(ruleEvaluationText('MAX_EVENTS_YEAR', 'events12m=1 max=2')).toBe(
        '1 siniestro en los últimos 12 meses · máximo 2',
      );
      expect(ruleEvaluationText('MAX_EVENTS_YEAR', 'events12m=4 max=2')).toBe(
        '4 siniestros en los últimos 12 meses · máximo 2',
      );
    });

    it('deja pasar lo que ya viene en prosa, y el literal crudo si no lo reconoce', () => {
      expect(ruleEvaluationText('FRAUD_RECORD', 'sin antecedentes vigentes (ventana 36m)')).toBe(
        'Sin antecedentes vigentes (ventana 36m)',
      );
      expect(ruleEvaluationText('REPORT_DEADLINE', 'formato=inesperado')).toBe(
        'formato=inesperado',
      );
      expect(ruleEvaluationText('POLICY_IN_FORCE', null)).toBe('—');
    });
  });

  describe('criterios de Fast Track (H0038)', () => {
    it('arma la frase de cada criterio con el valor que se comparó', () => {
      expect(ruleEvaluationText('FT_AMOUNT_RATIO', 'ratio=21.9% max=50.0%')).toBe(
        'Reclama el 21.9% de la suma asegurada · tope 50.0%',
      );
      expect(ruleEvaluationText('FT_PRIOR_CLAIMS', 'priorClaims=1 max=2 windowMonths=12')).toBe(
        '1 siniestro previo en los últimos 12 meses · máximo 2',
      );
      expect(ruleEvaluationText('FT_PRIOR_CLAIMS', 'priorClaims=3 max=2')).toBe(
        '3 siniestros previos · máximo 2',
      );
      expect(ruleEvaluationText('FT_POLICY_AGE', 'policyAgeMonths=27 min=6')).toBe(
        'Póliza de 27 meses · mínimo 6',
      );
      expect(ruleEvaluationText('FT_POLICY_UP_TO_DATE', 'upToDate=true')).toBe(
        'La póliza está al día',
      );
    });

    it('distingue el criterio que no se pudo evaluar del que se evaluó y falló', () => {
      expect(ruleEvaluationText('FT_AMOUNT_RATIO', 'ratio=sin datos max=50.0%')).toBe(
        'Sin monto reclamado o sin suma asegurada · tope 50.0%',
      );
      expect(ruleEvaluationText('FT_POLICY_AGE', 'policyAgeMonths=sin datos min=6')).toBe(
        'No se pudo determinar la antigüedad de la póliza · mínimo 6 meses',
      );
    });

    it('traduce los códigos de documento a lo que el analista conoce', () => {
      expect(ruleEvaluationText('FT_REQUIRED_DOCS', 'required=police_report missing=ninguno')).toBe(
        'Presente: Denuncia policial',
      );
      expect(
        ruleEvaluationText('FT_REQUIRED_DOCS', 'required=police_report,item_photo missing=item_photo'),
      ).toBe('Falta: Foto del bien');
    });

    it('separa los criterios del gate de las reglas duras', () => {
      expect(isFastTrackCriterion('FT_AMOUNT_RATIO')).toBe(true);
      expect(isFastTrackCriterion('POLICY_IN_FORCE')).toBe(false);
      // El tipo de la fila de configuración tampoco es un criterio evaluado.
      expect(isFastTrackCriterion('FAST_TRACK')).toBe(false);
    });
  });

  describe('ruleTypeLabel', () => {
    it('traduce los tipos conocidos y muestra el literal de los que no', () => {
      expect(ruleTypeLabel('POLICE_DEADLINE')).toBe('Plazo de la denuncia policial');
      expect(ruleTypeLabel('POLICY_STANDING')).toBe('Mora de la póliza');
      expect(ruleTypeLabel('FRAUD_RECORD')).toBe('Antecedente de fraude');
      expect(ruleTypeLabel('FT_AMOUNT_RATIO')).toBe('Monto reclamado sobre la suma asegurada');
      expect(ruleTypeLabel('REGLA_NUEVA')).toBe('REGLA_NUEVA');
    });
  });
});
