import {
  ruleEvaluationText,
  ruleResultLabel,
  ruleResultTone,
  ruleTypeLabel,
} from './trazabilidad';

describe('trazabilidad', () => {
  describe('ruleResultTone / ruleResultLabel', () => {
    it('entiende los dos vocabularios: el del motor y el del seed demo', () => {
      expect(ruleResultTone('PASS')).toBe('ok');
      expect(ruleResultTone('CUMPLE')).toBe('ok');
      expect(ruleResultTone('FAIL')).toBe('danger');
      expect(ruleResultTone('NO_CUMPLE')).toBe('danger');

      expect(ruleResultLabel('CUMPLE')).toBe('Cumple');
      expect(ruleResultLabel('NO_CUMPLE')).toBe('No cumple');
    });

    // El bug: todo lo que no era PASS se pintaba como fallo, y un Fast Track que había cumplido
    // salía en rojo diciendo "No cumple".
    it('no da por fallada una regla con un literal que no reconoce', () => {
      expect(ruleResultTone('LO_QUE_SEA')).toBe('neutral');
      expect(ruleResultLabel('LO_QUE_SEA')).toBe('LO_QUE_SEA');
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

  describe('ruleTypeLabel', () => {
    it('traduce los tipos conocidos y muestra el literal de los que no', () => {
      expect(ruleTypeLabel('POLICE_DEADLINE')).toBe('Plazo de la denuncia policial');
      expect(ruleTypeLabel('POLICY_STANDING')).toBe('Mora de la póliza');
      expect(ruleTypeLabel('FRAUD_RECORD')).toBe('Antecedente de fraude');
      expect(ruleTypeLabel('REGLA_NUEVA')).toBe('REGLA_NUEVA');
    });
  });
});
