/**
 * The referent's setup checklist ("Puesta en marcha"). The backend sends the steps and numbers;
 * every text the referent reads lives here, so the card, the toast and the section tooltips say
 * the same thing.
 */

export type OnboardingStepId =
  | 'COVERAGES'
  | 'ANALYSTS'
  | 'INSURED'
  | 'DOCUMENTS'
  | 'SCORING'
  | 'FAST_TRACK'
  | 'PROVIDERS'
  | 'AUTHORITIES'
  | 'HARD_STOP'
  | 'FRAUD'
  | 'TARGET'
  | 'COMMON_EXCLUSIONS'
  | 'BUSINESS_RULES';

export type OnboardingLevel = 'REQUIRED' | 'RECOMMENDED' | 'OPTIONAL';
export type OnboardingStatus = 'PENDING' | 'DONE' | 'SKIPPED';

export interface OnboardingStep {
  step: OnboardingStepId;
  level: OnboardingLevel;
  branchId: number | null;
  branchName: string | null;
  status: OnboardingStatus;
  count: number | null;
  secondaryCount: number | null;
}

export const ONBOARDING_LEVEL_LABEL: Record<OnboardingLevel, string> = {
  REQUIRED: 'Obligatorio',
  RECOMMENDED: 'Recomendado',
  OPTIONAL: 'Opcional',
};

/** What the referent does in the step. Also the tooltip of the step's section in Reglas. */
export const ONBOARDING_STEP_LINE: Record<OnboardingStepId, string> = {
  COVERAGES: 'Elegí las coberturas de las pólizas y configurá sus reglas.',
  ANALYSTS: 'Cargá el nombre y el mail de cada analista.',
  INSURED: 'Invitá a los asegurados con póliza vigente.',
  DOCUMENTS: 'Marcá los documentos necesarios para denunciar cada hecho generador.',
  SCORING: 'Elegí los factores de riesgo y el peso de cada uno.',
  FAST_TRACK: 'Definí las condiciones para que una denuncia entre en Fast Track.',
  PROVIDERS: 'Agregá los peritos y servicios técnicos para derivar los expedientes.',
  AUTHORITIES: 'Definí el monto máximo que un analista puede autorizar por cada ramo.',
  HARD_STOP: 'Elegí las reglas que se evalúan al dar de alta la denuncia.',
  FRAUD:
    'Definí por cuántos meses un antecedente de fraude afecta las denuncias nuevas del asegurado.',
  TARGET: 'Definí el plazo de resolución de los expedientes.',
  COMMON_EXCLUSIONS: 'Escribí las situaciones no contempladas en ninguna cobertura del ramo.',
  BUSINESS_RULES: 'Agregá los criterios de evaluación para el analista y la IA.',
};

export function onboardingStepTitle(s: Pick<OnboardingStep, 'step' | 'branchName'>): string {
  switch (s.step) {
    case 'COVERAGES':
      return `Cargá las coberturas de ${s.branchName ?? 'este ramo'}`;
    case 'ANALYSTS':
      return 'Dá de alta a los analistas';
    case 'INSURED':
      return 'Habilitá a los asegurados';
    case 'DOCUMENTS':
      return 'Armá la agenda documental';
    case 'SCORING':
      return 'Configurá el puntaje de riesgo';
    case 'FAST_TRACK':
      return 'Configurá el Fast Track';
    case 'PROVIDERS':
      return 'Cargá los proveedores externos';
    case 'AUTHORITIES':
      return 'Fijá las atribuciones de liquidación';
    case 'HARD_STOP':
      return 'Configurá el Hard Stop';
    case 'FRAUD':
      return 'Configurá la gestión de fraude';
    case 'TARGET':
      return 'Fijá el objetivo de resolución';
    case 'COMMON_EXCLUSIONS':
      return 'Cargá las exclusiones comunes';
    case 'BUSINESS_RULES':
      return 'Escribí las reglas de negocio';
  }
}

function plural(n: number, one: string, many: string): string {
  return n === 1 ? one : many.replace('{n}', String(n));
}

/** What a completed step shows instead of its line. */
export function onboardingStepSummary(s: OnboardingStep): string {
  const n = s.count ?? 0;
  switch (s.step) {
    case 'COVERAGES':
      return plural(n, '1 cobertura', '{n} coberturas');
    case 'ANALYSTS': {
      const analysts = plural(n, '1 analista', '{n} analistas');
      const inactive = s.secondaryCount ?? 0;
      return inactive > 0 ? `${analysts} · ${inactive} sin activar` : analysts;
    }
    case 'INSURED':
      return plural(n, '1 habilitado', '{n} habilitados');
    case 'DOCUMENTS':
      return plural(n, '1 hecho con documentos', '{n} hechos con documentos');
    case 'SCORING':
      return plural(n, '1 factor activo', '{n} factores activos');
    case 'FAST_TRACK':
      return plural(n, 'En 1 cobertura', 'En {n} coberturas');
    case 'PROVIDERS':
      return plural(n, '1 proveedor', '{n} proveedores');
    case 'AUTHORITIES':
      return plural(n, 'Tope en 1 ramo', 'Tope en {n} ramos');
    case 'HARD_STOP':
      return plural(n, '1 regla activa', '{n} reglas activas');
    case 'FRAUD':
      return `${n} meses`;
    case 'TARGET':
      return `${n} días`;
    case 'COMMON_EXCLUSIONS':
      return plural(n, '1 exclusión', '{n} exclusiones');
    case 'BUSINESS_RULES':
      return plural(n, '1 regla', '{n} reglas');
  }
}

/** Where each step is configured: a Reglas section (with branch and tab) or Usuarios. */
export interface OnboardingRoute {
  path: string;
  queryParams?: Record<string, string>;
}

export function onboardingStepRoute(
  s: OnboardingStep,
  firstBranchId: number | null,
): OnboardingRoute {
  const branch = (tab: string): OnboardingRoute => {
    const id = s.branchId ?? firstBranchId;
    return {
      path: '/insurer/rules',
      queryParams: id == null ? { solapa: tab } : { ramo: String(id), solapa: tab },
    };
  };
  const section = (name: string): OnboardingRoute => ({
    path: '/insurer/rules',
    queryParams: { seccion: name },
  });
  switch (s.step) {
    case 'COVERAGES':
      return branch('coverages');
    case 'ANALYSTS':
    case 'INSURED':
      return { path: '/insurer/users' };
    case 'DOCUMENTS':
      return branch('documents');
    case 'FAST_TRACK':
      return branch('fastTrack');
    case 'COMMON_EXCLUSIONS':
      return branch('exclusions');
    case 'BUSINESS_RULES':
      return branch('rules');
    case 'SCORING':
      return section('scoring');
    case 'PROVIDERS':
      return section('providers');
    case 'AUTHORITIES':
      return section('authorities');
    case 'HARD_STOP':
      return section('hardStop');
    case 'FRAUD':
      return section('fraud');
    case 'TARGET':
      return section('target');
  }
}

/** The menu entry whose screen configures the step, for the pending dot. */
export function onboardingStepArea(step: OnboardingStepId): 'rules' | 'users' {
  return step === 'ANALYSTS' || step === 'INSURED' ? 'users' : 'rules';
}

/** One key per checklist entry: coverages repeat once per branch. */
export function onboardingStepKey(s: Pick<OnboardingStep, 'step' | 'branchId'>): string {
  return s.branchId == null ? s.step : `${s.step}:${s.branchId}`;
}
