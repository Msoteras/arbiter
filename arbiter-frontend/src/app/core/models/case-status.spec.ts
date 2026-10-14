import { caseStatusLabel, caseStatusTone, isFinalStatus } from './case-status';
import { historyNote } from './history-note';

describe('closing statuses', () => {
  it('names each one by what ended the case', () => {
    expect(caseStatusLabel('PRESCRIBED')).toBe('Prescripto');
    expect(caseStatusLabel('WITHDRAWN')).toBe('Desistido');
    expect(caseStatusLabel('VOIDED')).toBe('Baja por error');
  });

  it('marks all three as final, so the inbox counts them as closed', () => {
    expect(isFinalStatus('PRESCRIBED')).toBeTrue();
    expect(isFinalStatus('WITHDRAWN')).toBeTrue();
    expect(isFinalStatus('VOIDED')).toBeTrue();
  });

  it('colors a prescription like a rejection and a closure with no accent', () => {
    expect(caseStatusTone('PRESCRIBED')).toBe('danger');
    expect(caseStatusTone('WITHDRAWN')).toBe('neutral');
    expect(caseStatusTone('VOIDED')).toBe('neutral');
  });

  it('reads the prescription decision on the history in Spanish', () => {
    expect(historyNote('decisión del analista: PRESCRIBE')).toBe(
      'decisión del analista: Rechazado por prescripción',
    );
  });
});
