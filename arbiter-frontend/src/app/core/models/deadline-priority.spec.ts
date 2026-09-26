import {
  DeadlinePriority,
  deadlinePriorityLabel,
  deadlinePriorityTone,
  isDeadlinePrioritized,
} from './deadline-priority';

function isoDaysFromToday(days: number): string {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() + days);
  return d.toISOString().slice(0, 10);
}

describe('deadline-priority', () => {
  describe('deadlinePriorityTone', () => {
    it('maps each level to its status tone', () => {
      expect(deadlinePriorityTone('NONE')).toBe('neutral');
      expect(deadlinePriorityTone('WATCH')).toBe('warning');
      expect(deadlinePriorityTone('URGENT')).toBe('risk');
      expect(deadlinePriorityTone('CRITICAL')).toBe('danger');
      expect(deadlinePriorityTone('OVERDUE')).toBe('danger');
    });
  });

  describe('isDeadlinePrioritized', () => {
    it('is true only for the levels that show a chip', () => {
      expect(isDeadlinePrioritized('WATCH')).toBeTrue();
      expect(isDeadlinePrioritized('URGENT')).toBeTrue();
      expect(isDeadlinePrioritized('CRITICAL')).toBeTrue();
      expect(isDeadlinePrioritized('OVERDUE')).toBeTrue();
      expect(isDeadlinePrioritized('NONE')).toBeFalse();
    });

    it('treats missing or unknown values as unflagged (older backend)', () => {
      expect(isDeadlinePrioritized(undefined as unknown as DeadlinePriority)).toBeFalse();
      expect(isDeadlinePrioritized('WHATEVER' as unknown as DeadlinePriority)).toBeFalse();
    });
  });

  describe('deadlinePriorityLabel', () => {
    it('NONE has no text', () => {
      expect(deadlinePriorityLabel('NONE', isoDaysFromToday(20))).toBe('');
    });

    it('OVERDUE reads "Vencido" whatever the date', () => {
      expect(deadlinePriorityLabel('OVERDUE', isoDaysFromToday(-5))).toBe('Vencido');
    });

    it('counts the remaining days as today, tomorrow or plural', () => {
      expect(deadlinePriorityLabel('CRITICAL', isoDaysFromToday(0))).toBe('Vence hoy');
      expect(deadlinePriorityLabel('CRITICAL', isoDaysFromToday(1))).toBe('Vence mañana');
      expect(deadlinePriorityLabel('URGENT', isoDaysFromToday(4))).toBe('Vence en 4 días');
      expect(deadlinePriorityLabel('WATCH', isoDaysFromToday(9))).toBe('Vence en 9 días');
    });
  });
});
