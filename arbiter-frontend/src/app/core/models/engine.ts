import { Classification, classificationLabel, classificationTone } from './classification';
import { StatusTone } from './status-tone';

// Mirrors common-lib's ClassificationStage.
export type ClassificationStage =
  | 'QUEUED'
  | 'POLICY'
  | 'HISTORY'
  | 'RULES'
  | 'EXCLUSION'
  | 'DOCUMENT'
  | 'RULE_EVALUATION'
  | 'FAST_TRACK'
  | 'DOCUMENT_SCHEDULE'
  | 'MODEL'
  | 'IMAGES'
  | 'RISK'
  | 'FINISHED'
  | 'FAILED';

/** Mirrors cases-service's EngineCaseResponse: a case's latest classification run, on its last step. */
export interface EngineCase {
  caseId: number;
  insured: string | null;
  claimCause: string | null;
  stage: ClassificationStage;
  /** DOCUMENT: the document type code. */
  detail: string | null;
  itemIndex: number | null;
  itemCount: number | null;
  /** Only once FINISHED. */
  result: Classification | null;
  queuedAt: string;
  /** Null while it waits in the queue. */
  startedAt: string | null;
  /** When the current step started. */
  stageAt: string;
}

/** Mirrors cases-service's EngineBoardResponse. */
export interface EngineBoard {
  active: EngineCase[];
  classifiedToday: EngineCase[];
  serverTime: string;
  topic: string;
}

/** Who does the step: the data sources, the rule engine or the model. The board draws each apart. */
export type StageActor = 'data' | 'rule' | 'model';

export type StageIcon =
  | 'policy'
  | 'history'
  | 'rules'
  | 'exclusion'
  | 'document'
  | 'fastTrack'
  | 'schedule'
  | 'model'
  | 'image'
  | 'risk'
  | 'result';

interface StageDef {
  text: string;
  actor: StageActor;
  icon: StageIcon;
}

const RUNNING: Record<Exclude<ClassificationStage, 'QUEUED' | 'FINISHED' | 'FAILED'>, StageDef> = {
  POLICY: { text: 'Leyendo la póliza…', actor: 'data', icon: 'policy' },
  HISTORY: { text: 'Cruzando el historial…', actor: 'data', icon: 'history' },
  RULES: { text: 'Cargando las reglas…', actor: 'rule', icon: 'rules' },
  EXCLUSION: { text: 'Controlando exclusión y prescripción…', actor: 'rule', icon: 'exclusion' },
  DOCUMENT: { text: 'Leyendo la documentación…', actor: 'model', icon: 'document' },
  RULE_EVALUATION: { text: 'Evaluando las reglas…', actor: 'rule', icon: 'rules' },
  FAST_TRACK: { text: 'Controlando Fast Track…', actor: 'rule', icon: 'fastTrack' },
  DOCUMENT_SCHEDULE: { text: 'Revisando la agenda documental…', actor: 'rule', icon: 'schedule' },
  MODEL: { text: 'Interpretando el caso…', actor: 'model', icon: 'model' },
  IMAGES: { text: 'Comparando las imágenes…', actor: 'data', icon: 'image' },
  RISK: { text: 'Calculando el riesgo de fraude…', actor: 'data', icon: 'risk' },
};

const FALLBACK: StageDef = { text: 'Clasificando…', actor: 'data', icon: 'result' };

export function isQueued(c: EngineCase): boolean {
  return c.stage === 'QUEUED';
}

export function isDone(c: EngineCase): boolean {
  return c.stage === 'FINISHED' || c.stage === 'FAILED';
}

/** What a running case is doing. Unknown stages (a newer backend) read as plain "Clasificando…". */
export function stageDef(stage: ClassificationStage): StageDef {
  return (RUNNING as Record<string, StageDef>)[stage] ?? FALLBACK;
}

export function engineResultLabel(c: EngineCase): string {
  if (c.stage === 'FAILED') return 'Clasificación fallida';
  return c.result ? classificationLabel(c.result) : 'Clasificado';
}

export function engineResultTone(c: EngineCase): StatusTone {
  if (c.stage === 'FAILED') return 'warning';
  return c.result ? classificationTone(c.result) : 'neutral';
}
