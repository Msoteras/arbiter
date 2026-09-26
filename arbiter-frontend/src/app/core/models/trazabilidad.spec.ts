import {
  advisoryResultLabel,
  advisoryResultTone,
  isAdvisoryCheck,
  isFastTrackCriterion,
  ruleEvaluationText,
  ruleResultLabel,
  ruleResultTone,
  ruleTypeLabel,
} from './trazabilidad';

describe('trazabilidad', () => {
  describe('ruleResultTone / ruleResultLabel', () => {
    it('translates the engine vocabulary', () => {
      expect(ruleResultTone('PASS')).toBe('ok');
      expect(ruleResultTone('FAIL')).toBe('danger');

      expect(ruleResultLabel('PASS')).toBe('Cumple');
      expect(ruleResultLabel('FAIL')).toBe('No cumple');
    });

    it('treats old vocabulary like any unknown literal', () => {
      expect(ruleResultTone('CUMPLE')).toBe('neutral');
      expect(ruleResultLabel('CUMPLE')).toBe('CUMPLE');
    });

    it('does not mark a rule as failed when its literal is unknown', () => {
      expect(ruleResultTone('LO_QUE_SEA')).toBe('neutral');
      expect(ruleResultLabel('LO_QUE_SEA')).toBe('LO_QUE_SEA');
    });
  });

  // Advisory check: it decides nothing, so it must not read as a failed rule.
  describe('advisories', () => {
    it('are told apart from the rules and the Fast Track criteria', () => {
      expect(isAdvisoryCheck('CLAIM_CAUSE_MATCH')).toBe(true);
      expect(isAdvisoryCheck('COVERS_FAMILY_GROUP')).toBe(false);
      expect(isFastTrackCriterion('CLAIM_CAUSE_MATCH')).toBe(false);
    });

    it('a FAIL reads "Revisar" in yellow, not "No cumple" in red', () => {
      expect(advisoryResultLabel('FAIL')).toBe('Revisar');
      expect(advisoryResultTone('FAIL')).toBe('warning');
      expect(advisoryResultLabel('PASS')).toBe('Coincide');
      expect(advisoryResultTone('PASS')).toBe('ok');
    });

    it('says which document narrates which event, naming the document in Spanish', () => {
      expect(ruleTypeLabel('CLAIM_CAUSE_MATCH')).toBe('Hecho que narra la documentación');
      expect(
        ruleEvaluationText(
          'CLAIM_CAUSE_MATCH',
          'declared=Robo en vía pública described=Hurto documents=police_report',
        ),
      ).toBe('Denuncia policial: narra Hurto · se declaró Robo en vía pública');
      expect(
        ruleEvaluationText(
          'CLAIM_CAUSE_MATCH',
          'declared=Hurto described=Hurto documents=police_report',
        ),
      ).toBe('Denuncia policial: narra el hecho declarado (Hurto)');
    });
  });

  describe('coverage scope rules', () => {
    it('names them in Spanish', () => {
      expect(ruleTypeLabel('COVERS_FAMILY_GROUP')).toBe('Alcance al grupo familiar');
      expect(ruleTypeLabel('CLAIM_EXHAUSTS_COVERAGE')).toBe(
        'Cobertura consumida por un siniestro previo',
      );
    });

    it('names the affected party in Spanish', () => {
      expect(ruleEvaluationText('COVERS_FAMILY_GROUP', 'affectedParty=FAMILIAR')).toBe(
        'Damnificado: un familiar · la cobertura no alcanza al grupo familiar',
      );
      expect(ruleEvaluationText('COVERS_FAMILY_GROUP', 'affectedParty=TITULAR')).toBe(
        'Damnificado: el titular · la cobertura no alcanza al grupo familiar',
      );
    });

    it('tells zero apart from missing data when counting settled claims', () => {
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
    it('builds each rule type sentence with the numbers of the literal', () => {
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

    it('uses the singular for a one-event cap', () => {
      expect(ruleEvaluationText('MAX_EVENTS_YEAR', 'events12m=1 max=2')).toBe(
        '1 siniestro en los últimos 12 meses · máximo 2',
      );
      expect(ruleEvaluationText('MAX_EVENTS_YEAR', 'events12m=4 max=2')).toBe(
        '4 siniestros en los últimos 12 meses · máximo 2',
      );
    });

    it('passes prose through, and the raw literal when unknown', () => {
      expect(ruleEvaluationText('FRAUD_RECORD', 'sin antecedentes vigentes (ventana 36m)')).toBe(
        'Sin antecedentes vigentes en los últimos 36 meses',
      );
      expect(ruleEvaluationText('REPORT_DEADLINE', 'formato=inesperado')).toBe(
        'formato=inesperado',
      );
      expect(ruleEvaluationText('POLICY_IN_FORCE', null)).toBe('—');
    });
  });

  describe('Fast Track criteria (H0038)', () => {
    it('builds each criterion sentence with the compared value', () => {
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

    it('tells a criterion that could not be evaluated apart from one that failed', () => {
      expect(ruleEvaluationText('FT_AMOUNT_RATIO', 'ratio=sin datos max=50.0%')).toBe(
        'Sin monto reclamado o sin suma asegurada · tope 50.0%',
      );
      expect(ruleEvaluationText('FT_POLICY_AGE', 'policyAgeMonths=sin datos min=6')).toBe(
        'No se pudo determinar la antigüedad de la póliza · mínimo 6 meses',
      );
    });

    it('translates document codes into what the analyst knows', () => {
      expect(ruleEvaluationText('FT_REQUIRED_DOCS', 'required=police_report missing=ninguno')).toBe(
        'Presente: Denuncia policial',
      );
      expect(
        ruleEvaluationText(
          'FT_REQUIRED_DOCS',
          'required=police_report,item_photo missing=item_photo',
        ),
      ).toBe('Falta: Foto del bien');
    });

    it('separates the gate criteria from the hard rules', () => {
      expect(isFastTrackCriterion('FT_AMOUNT_RATIO')).toBe(true);
      expect(isFastTrackCriterion('POLICY_IN_FORCE')).toBe(false);
      // The configuration row type is not an evaluated criterion either.
      expect(isFastTrackCriterion('FAST_TRACK')).toBe(false);
    });
  });

  describe('ruleTypeLabel', () => {
    it('translates known types and shows the literal of unknown ones', () => {
      expect(ruleTypeLabel('POLICE_DEADLINE')).toBe('Plazo de la denuncia policial');
      expect(ruleTypeLabel('POLICY_STANDING')).toBe('Mora de la póliza');
      expect(ruleTypeLabel('FRAUD_RECORD')).toBe('Antecedente de fraude');
      expect(ruleTypeLabel('FT_AMOUNT_RATIO')).toBe('Monto reclamado sobre la suma asegurada');
      expect(ruleTypeLabel('REGLA_NUEVA')).toBe('REGLA_NUEVA');
    });
  });
});
