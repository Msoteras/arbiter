/**
 * Spanish label + description for each evaluable rule type — mirrors common-lib's `RuleType`.
 *
 * The backend sends the literal only as a fallback: `byBlockingRule` prefers the insurer's own
 * `insurer_rule.name` (free text the referente typed) and falls back to the raw enum literal only
 * for rules with no `insurer_rule` row — the coverage-scope checks and every Fast Track criterion
 * (`FT_*`), which is why those were the ones showing up in English on the dashboard.
 */
const RULE_TYPE_INFO: Record<string, { label: string; description: string }> = {
  COVERAGE_EXCLUSION: {
    label: 'Exclusión de cobertura',
    description:
      'El hecho generador denunciado no está entre los que cubre la cobertura contratada.',
  },
  POLICY_IN_FORCE: {
    label: 'Vigencia de la póliza',
    description: 'El hecho ocurrió fuera del período de vigencia de la póliza.',
  },
  WAITING_PERIOD: {
    label: 'Carencia',
    description: 'El hecho ocurrió dentro del período de carencia de la cobertura.',
  },
  REPORT_DEADLINE: {
    label: 'Plazo de denuncia',
    description:
      'La denuncia se presentó fuera del plazo que la cobertura le da al asegurado para avisar del hecho.',
  },
  POLICE_DEADLINE: {
    label: 'Plazo de la denuncia policial',
    description: 'La denuncia policial se hizo fuera del plazo permitido desde el hecho.',
  },
  MAX_EVENTS_YEAR: {
    label: 'Tope de eventos por año',
    description:
      'El asegurado superó la cantidad máxima de siniestros del ramo en los últimos 12 meses.',
  },
  POLICY_STANDING: {
    label: 'Mora de la póliza',
    description: 'La póliza no está al día con sus pagos al momento del hecho.',
  },
  FRAUD_RECORD: {
    label: 'Antecedente de fraude',
    description:
      'El asegurado tiene un antecedente de fraude dentro de la ventana configurada por la aseguradora.',
  },
  COVERS_FAMILY_GROUP: {
    label: 'Alcance familiar de la cobertura',
    description:
      'El damnificado no es el titular ni el grupo familiar conviviente que alcanza la cobertura.',
  },
  CLAIM_EXHAUSTS_COVERAGE: {
    label: 'Cobertura agotada',
    description: 'La cobertura ya se agotó por siniestros anteriores en el período vigente.',
  },
  FT_AMOUNT_RATIO: {
    label: 'Fast Track: monto reclamado',
    description:
      'El monto reclamado supera el porcentaje de la suma asegurada que habilita el trámite rápido.',
  },
  FT_PRIOR_CLAIMS: {
    label: 'Fast Track: siniestros previos',
    description:
      'El asegurado superó la cantidad de siniestros previos que admite el trámite rápido.',
  },
  FT_POLICY_AGE: {
    label: 'Fast Track: antigüedad de la póliza',
    description: 'La póliza no alcanza la antigüedad mínima que exige el trámite rápido.',
  },
  FT_POLICY_UP_TO_DATE: {
    label: 'Fast Track: póliza al día',
    description: 'La póliza no está al día con los pagos, requisito del trámite rápido.',
  },
  FT_REQUIRED_DOCS: {
    label: 'Fast Track: documentación',
    description: 'Faltan documentos requeridos, o no son legibles, para el trámite rápido.',
  },
  // Aviso, no regla: el tablero de "reglas que frenaron" lo excluye (no frenó nada). Está para que
  // el mapa siga reflejando todo RuleType.
  CLAIM_CAUSE_MATCH: {
    label: 'Hecho que narra la documentación',
    description:
      'La documentación narra un hecho generador distinto del declarado. Es un aviso para el analista: no bloquea el trámite.',
  },
};

/**
 * Traduce un literal de `RuleType`. Si `raw` no está en el mapa, se devuelve tal cual: puede ser
 * ya un nombre en castellano que el referente le puso a su regla (`insurer_rule.name`).
 */
export function ruleTypeLabel(raw: string): string {
  return RULE_TYPE_INFO[raw]?.label ?? raw;
}

/** Descripción de qué evalúa la regla, para un tooltip. `undefined` si `raw` no es un literal conocido. */
export function ruleTypeDescription(raw: string): string | undefined {
  return RULE_TYPE_INFO[raw]?.description;
}
