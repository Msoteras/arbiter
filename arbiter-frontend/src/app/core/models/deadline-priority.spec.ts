import {
  DeadlinePriority,
  deadlinePriorityLabel,
  deadlinePriorityTone,
  isDeadlinePrioritized,
} from './deadline-priority';

/** ISO (yyyy-MM-dd) a N días de hoy — para los labels que dependen de la fecha actual. */
function isoDaysFromToday(days: number): string {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() + days);
  return d.toISOString().slice(0, 10);
}

describe('deadline-priority', () => {
  describe('deadlinePriorityTone', () => {
    it('mapea cada nivel a su tono del semáforo', () => {
      expect(deadlinePriorityTone('NONE')).toBe('neutral');
      expect(deadlinePriorityTone('WATCH')).toBe('warning');
      expect(deadlinePriorityTone('URGENT')).toBe('risk');
      expect(deadlinePriorityTone('CRITICAL')).toBe('danger');
      expect(deadlinePriorityTone('OVERDUE')).toBe('danger');
    });
  });

  describe('isDeadlinePrioritized', () => {
    it('es true solo para los niveles con chip', () => {
      expect(isDeadlinePrioritized('WATCH')).toBeTrue();
      expect(isDeadlinePrioritized('URGENT')).toBeTrue();
      expect(isDeadlinePrioritized('CRITICAL')).toBeTrue();
      expect(isDeadlinePrioritized('OVERDUE')).toBeTrue();
      expect(isDeadlinePrioritized('NONE')).toBeFalse();
    });

    it('trata valores ausentes/desconocidos como sin marca (backend viejo)', () => {
      expect(isDeadlinePrioritized(undefined as unknown as DeadlinePriority)).toBeFalse();
      expect(isDeadlinePrioritized('WHATEVER' as unknown as DeadlinePriority)).toBeFalse();
    });
  });

  describe('deadlinePriorityLabel', () => {
    it('NONE no tiene texto', () => {
      expect(deadlinePriorityLabel('NONE', isoDaysFromToday(20))).toBe('');
    });

    it('OVERDUE es "Vencido" sin importar la fecha', () => {
      expect(deadlinePriorityLabel('OVERDUE', isoDaysFromToday(-5))).toBe('Vencido');
    });

    it('cuenta los días restantes con hoy/mañana/plural', () => {
      expect(deadlinePriorityLabel('CRITICAL', isoDaysFromToday(0))).toBe('Vence hoy');
      expect(deadlinePriorityLabel('CRITICAL', isoDaysFromToday(1))).toBe('Vence mañana');
      expect(deadlinePriorityLabel('URGENT', isoDaysFromToday(4))).toBe('Vence en 4 días');
      expect(deadlinePriorityLabel('WATCH', isoDaysFromToday(9))).toBe('Vence en 9 días');
    });
  });
});
