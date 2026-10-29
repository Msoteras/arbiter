import { EngineCase } from '../../core/models/engine';
import {
  EMPTY_STATE,
  HOLD_MS,
  applyStep,
  estimateProgress,
  expireHolds,
  fromBoard,
  resync,
} from './engine-state';

function engineCase(
  caseId: number,
  stage: EngineCase['stage'],
  extra: Partial<EngineCase> = {},
): EngineCase {
  return {
    caseId,
    insured: 'Martina Soteras',
    claimCause: 'Hurto',
    stage,
    detail: null,
    itemIndex: null,
    itemCount: null,
    result: null,
    queuedAt: '2026-10-25T15:00:00Z',
    startedAt: stage === 'QUEUED' ? null : '2026-10-25T15:00:01Z',
    stageAt: '2026-10-25T15:00:05Z',
    ...extra,
  };
}

describe('engine state', () => {
  it('replaces a case by id with each step', () => {
    let state = applyStep(EMPTY_STATE, engineCase(7, 'QUEUED'), 0);
    state = applyStep(state, engineCase(7, 'POLICY'), 0);

    expect(state.active.length).toBe(1);
    expect(state.active[0].stage).toBe('POLICY');
  });

  it('holds a case that finishes while running, then moves it to the pool', () => {
    let state = applyStep(EMPTY_STATE, engineCase(7, 'MODEL'), 0);
    state = applyStep(state, engineCase(7, 'FINISHED', { result: 'FAST_TRACK' }), 1000);

    expect(state.active.map((c) => c.caseId)).toEqual([7]);
    expect(state.ending.get(7)).toBe(1000 + HOLD_MS);
    expect(expireHolds(state, 1000 + HOLD_MS - 1)).toBe(state);

    const later = expireHolds(state, 1000 + HOLD_MS);
    expect(later.active).toEqual([]);
    expect(later.done.map((c) => c.caseId)).toEqual([7]);
    expect(later.ending.size).toBe(0);
  });

  it('sends a case it never saw running straight to the pool', () => {
    const state = applyStep(EMPTY_STATE, engineCase(7, 'FAILED'), 0);

    expect(state.active).toEqual([]);
    expect(state.done.map((c) => c.caseId)).toEqual([7]);
  });

  it('takes a reclassified case out of the pool', () => {
    const state = applyStep(fromBoard([], [engineCase(7, 'FINISHED')]), engineCase(7, 'QUEUED'), 0);

    expect(state.done).toEqual([]);
    expect(state.active.map((c) => c.stage)).toEqual(['QUEUED']);
  });

  it('keeps the cases in their hold through a resync', () => {
    let state = applyStep(EMPTY_STATE, engineCase(7, 'MODEL'), 0);
    state = applyStep(state, engineCase(7, 'FINISHED'), 0);

    const synced = resync(state, [engineCase(8, 'QUEUED')], [engineCase(7, 'FINISHED')]);

    expect(synced.active.map((c) => c.caseId)).toEqual([8, 7]);
    expect(synced.done).toEqual([]);
  });

  describe('progress estimate', () => {
    it('moves forward inside a step and never past its end', () => {
      const policy = engineCase(7, 'POLICY');
      const early = estimateProgress(policy, 0, false);
      const late = estimateProgress(policy, 60_000, false);

      expect(early).toBe(0);
      expect(late).toBeGreaterThan(early);
      expect(late).toBeLessThanOrEqual(0.03);
    });

    it('splits the document span among the documents of the pass', () => {
      const first = estimateProgress(
        engineCase(7, 'DOCUMENT', { itemIndex: 1, itemCount: 2 }),
        0,
        false,
      );
      const second = estimateProgress(
        engineCase(7, 'DOCUMENT', { itemIndex: 2, itemCount: 2 }),
        0,
        false,
      );

      expect(second).toBeGreaterThan(first);
    });

    it('places the second reading pass after the Fast Track gate', () => {
      const gate = estimateProgress(
        engineCase(7, 'DOCUMENT', { itemIndex: 1, itemCount: 1 }),
        60_000,
        false,
      );
      const rest = estimateProgress(
        engineCase(7, 'DOCUMENT', { itemIndex: 1, itemCount: 1 }),
        0,
        true,
      );

      expect(rest).toBeGreaterThan(gate);
    });

    it('is complete once the case is done', () => {
      expect(estimateProgress(engineCase(7, 'FINISHED'), 0, false)).toBe(1);
    });
  });
});
