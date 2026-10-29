import { ClassificationStage, EngineCase, isDone } from '../../core/models/engine';

/** How long a finished case stays in the ring, showing its result, before it moves to the pool. */
export const HOLD_MS = 1800;

/** The pool shows the latest ones; the counter still counts them all. */
export const POOL_MAX = 20;

export interface EngineState {
  /** Queued or running, as the server last described them. */
  active: readonly EngineCase[];
  /** Classified today, latest first. */
  done: readonly EngineCase[];
  /** Finished cases still shown in the ring, until the time in ms. */
  ending: ReadonlyMap<number, number>;
}

export const EMPTY_STATE: EngineState = { active: [], done: [], ending: new Map() };

export function fromBoard(active: EngineCase[], done: EngineCase[]): EngineState {
  return { active, done, ending: new Map() };
}

/**
 * Folds one step into the board. A step is the whole case after it, so it replaces the case by id.
 * A case that finishes while running holds its place for HOLD_MS; one that finishes without the
 * board having seen it run goes straight to the pool.
 */
export function applyStep(state: EngineState, step: EngineCase, nowMs: number): EngineState {
  const others = state.active.filter((c) => c.caseId !== step.caseId);
  const wasRunning = state.active.some((c) => c.caseId === step.caseId && c.stage !== 'QUEUED');

  if (!isDone(step)) {
    // A reclassification puts a case back in the queue: it leaves the pool.
    return {
      active: [...others, step],
      done: state.done.filter((c) => c.caseId !== step.caseId),
      ending: withoutKey(state.ending, step.caseId),
    };
  }

  if (wasRunning) {
    const ending = new Map(state.ending);
    ending.set(step.caseId, nowMs + HOLD_MS);
    return { active: [...others, step], done: state.done, ending };
  }
  return {
    active: others,
    done: [step, ...state.done.filter((c) => c.caseId !== step.caseId)],
    ending: state.ending,
  };
}

/** Moves the finished cases whose hold ran out from the ring to the pool. */
export function expireHolds(state: EngineState, nowMs: number): EngineState {
  const expired = [...state.ending].filter(([, until]) => until <= nowMs).map(([id]) => id);
  if (!expired.length) return state;
  const moving = state.active.filter((c) => expired.includes(c.caseId));
  const ending = new Map(state.ending);
  expired.forEach((id) => ending.delete(id));
  return {
    active: state.active.filter((c) => !expired.includes(c.caseId)),
    done: [...moving.reverse(), ...state.done.filter((c) => !expired.includes(c.caseId))],
    ending,
  };
}

/**
 * Keeps the cases still in their hold, which the snapshot already lists as done: without this a
 * resync would yank them out of the ring mid-animation.
 */
export function resync(state: EngineState, active: EngineCase[], done: EngineCase[]): EngineState {
  const holding = state.active.filter((c) => state.ending.has(c.caseId));
  const holdingIds = holding.map((c) => c.caseId);
  return {
    active: [...active.filter((c) => !holdingIds.includes(c.caseId)), ...holding],
    done: done.filter((c) => !holdingIds.includes(c.caseId)),
    ending: state.ending,
  };
}

function withoutKey(map: ReadonlyMap<number, number>, key: number): ReadonlyMap<number, number> {
  if (!map.has(key)) return map;
  const next = new Map(map);
  next.delete(key);
  return next;
}

/**
 * Where a step sits in a full run, as [start, end] of the line. The real length of a run is unknown
 * until it ends, so the line is an estimate: it only ever moves forward, and slows down inside a
 * step the longer that step takes. Documents are read twice in a run (the Fast Track gate, then the
 * rest), so DOCUMENT has a second span once the run has gone past the gate.
 */
const SPANS: Partial<Record<ClassificationStage, [number, number]>> = {
  POLICY: [0, 0.03],
  HISTORY: [0.03, 0.05],
  RULES: [0.05, 0.07],
  EXCLUSION: [0.07, 0.08],
  DOCUMENT: [0.08, 0.5],
  RULE_EVALUATION: [0.5, 0.52],
  FAST_TRACK: [0.52, 0.54],
  DOCUMENT_SCHEDULE: [0.54, 0.56],
  MODEL: [0.8, 0.94],
  IMAGES: [0.94, 0.98],
  RISK: [0.98, 1],
};
const SECOND_DOCUMENT_SPAN: [number, number] = [0.56, 0.8];
const PAST_THE_GATE: readonly ClassificationStage[] = [
  'RULE_EVALUATION',
  'FAST_TRACK',
  'DOCUMENT_SCHEDULE',
];

/** Typical length of a step, in ms; the line covers ~63% of a span in that time. */
function typicalMs(stage: ClassificationStage): number {
  if (stage === 'DOCUMENT') return 5000;
  if (stage === 'MODEL') return 6000;
  if (stage === 'IMAGES') return 1500;
  return 400;
}

/**
 * @param pastGate whether this run was already seen past the Fast Track gate
 * @returns 0..1
 */
export function estimateProgress(c: EngineCase, stageElapsedMs: number, pastGate: boolean): number {
  if (isDone(c)) return 1;
  let span = SPANS[c.stage];
  if (!span) return 0;
  if (c.stage === 'DOCUMENT') {
    const [from, to] = pastGate ? SECOND_DOCUMENT_SPAN : span;
    const count = Math.max(1, c.itemCount ?? 1);
    const index = Math.min(count, Math.max(1, c.itemIndex ?? 1));
    const width = (to - from) / count;
    span = [from + width * (index - 1), from + width * index];
  }
  const within = 1 - Math.exp(-Math.max(0, stageElapsedMs) / typicalMs(c.stage));
  return span[0] + (span[1] - span[0]) * within;
}

export function isPastGate(stage: ClassificationStage): boolean {
  return (
    PAST_THE_GATE.includes(stage) || stage === 'MODEL' || stage === 'IMAGES' || stage === 'RISK'
  );
}
