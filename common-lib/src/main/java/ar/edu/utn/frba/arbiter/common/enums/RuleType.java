package ar.edu.utn.frba.arbiter.common.enums;

import java.util.List;

/**
 * Rule vocabulary shared by {@code insurer_rule.rule_type} and {@code rule_result.rule_type}:
 * rules-service persists the literal and classification-service reads it to pick the evaluator.
 *
 * <p><b>Literals can't exceed 20 characters</b>: {@code insurer_rule.rule_type} is
 * {@code VARCHAR(20)}. That's why {@link #POLICE_DEADLINE} and not {@code POLICE_REPORT_DEADLINE}.
 *
 * <p>Three families:
 * <ul>
 *   <li><b>Configuration</b> ({@link #FAST_TRACK}, {@link #EXCLUSIONS}, {@link #BUSINESS_RULES},
 *       {@link #RESOLUTION_TARGET}) — parameters or free text, never evaluated, no {@code rule_result}.</li>
 *   <li><b>Hard evaluable rules</b> ({@link #COVERAGE_EXCLUSION} through
 *       {@link #CLAIM_EXHAUSTS_COVERAGE}) — each evaluation leaves an auditable {@code rule_result};
 *       failing one means the event isn't covered.</li>
 *   <li><b>Fast Track criteria</b> ({@code FT_*}) — audited the same way, but failing one only sends
 *       the claim to the LLM instead of the fast lane. Never {@code insurer_rule} rows.</li>
 *   <li><b>Advisory checks</b> ({@link #advisoryRules()}) — audited too, but failing one changes
 *       neither the coverage nor the fast lane: it flags something for the analyst to review.</li>
 * </ul>
 */
public enum RuleType {

    /** Fast Track gate thresholds, in the {@code configuration} JSONB. */
    FAST_TRACK,

    /** Branch-level free-text exclusions — go into the prompt, nobody evaluates them. */
    EXCLUSIONS,

    /** Branch-level free-text business rules — go into the prompt, nobody evaluates them. */
    BUSINESS_RULES,

    /**
     * The insurer's own target for resolving a claim ({@code targetDays}), read only by
     * reports-service. <b>Not the legal deadline</b> ({@code cases.response_deadline}): it's a
     * management goal that can be tighter than the law, and the two are measured separately.
     */
    RESOLUTION_TARGET,

    /** Blacklist of claim causes the coverage doesn't cover. */
    COVERAGE_EXCLUSION,

    /** The event has to fall within the policy's coverage window. */
    POLICY_IN_FORCE,

    /** The event can't fall within the coverage's waiting period. */
    WAITING_PERIOD,

    /** Deadline to report to the insurer, counted from the event. */
    REPORT_DEADLINE,

    /**
     * Deadline to file the police report, counted from the event. Its threshold lives in the
     * {@code configuration} JSONB because the coverage's only deadline column is already used by
     * {@link #REPORT_DEADLINE}.
     */
    POLICE_DEADLINE,

    /** Cap on the insured's claims in the branch over the trailing 12 months. */
    MAX_EVENTS_YEAR,

    /**
     * The policy isn't up to date with its payments ({@code policy.upToDate()}). Seeds inactive
     * because only the referente turns it on. Doesn't model the tiered arrears process (1/2/3
     * unpaid installments): that needs an installment ledger no schema has.
     */
    POLICY_STANDING,

    /**
     * The insured has a fraud record from an earlier claim. With no active row the engine ignores
     * fraud records entirely; {@code windowMonths} sets how long a record keeps counting.
     */
    FRAUD_RECORD,

    /**
     * Whether the coverage reaches the cohabiting family group or only the holder. This one and
     * {@link #CLAIM_EXHAUSTS_COVERAGE} are {@code coverage} columns, not {@code insurer_rule} rows,
     * which is why {@code rule_result.rule_id} is nullable.
     */
    COVERS_FAMILY_GROUP,

    /** Whether a settled claim exhausts the coverage for the period. Counted per policy, not per insured. */
    CLAIM_EXHAUSTS_COVERAGE,

    /**
     * Fast Track gate · claimed amount as a fraction of the sum insured, against the referente's
     * ceiling. The {@code FT_*} criteria carry no {@code rule_id} ({@code /internal/fast-track}
     * returns the config DTO without it), so the threshold goes in {@code evaluated_value}.
     */
    FT_AMOUNT_RATIO,

    /** Fast Track gate · the insured's prior claims in the configured window, against the cap. */
    FT_PRIOR_CLAIMS,

    /** Fast Track gate · the policy's age at the time of the event, against the minimum. */
    FT_POLICY_AGE,

    /** Fast Track gate · whether the policy is up to date with its payments, when required. */
    FT_POLICY_UP_TO_DATE,

    /** Fast Track gate · whether the documents the gate requires are attached and readable. */
    FT_REQUIRED_DOCS,

    /**
     * Advisory · whether the claim cause a document narrates is the one the insured declared. The
     * extraction reads it, {@code ClaimCauseConsistencyEvaluator} compares it. On the Fast Track
     * path the LLM never runs, so this is the only check of the account against the declared cause.
     * It warns and never blocks.
     */
    CLAIM_CAUSE_MATCH;

    /**
     * The hard rules {@code TemporalRuleEvaluator} evaluates. {@link #COVERAGE_EXCLUSION} is left
     * out on purpose: it's configured per coverage with its own claim cause selector.
     */
    public static List<RuleType> temporalRules() {
        return List.of(POLICY_IN_FORCE, WAITING_PERIOD, REPORT_DEADLINE, POLICE_DEADLINE,
                MAX_EVENTS_YEAR, POLICY_STANDING);
    }

    /** Temporal rules whose {@code insurer_rule} row is scoped to one (branch, coverage). */
    public static List<RuleType> coverageScoped() {
        return List.of(WAITING_PERIOD, REPORT_DEADLINE, POLICE_DEADLINE, MAX_EVENTS_YEAR);
    }

    /**
     * Temporal rules stored insurer-wide ({@code branch_id} and {@code coverage_id} null): it's the
     * policy as a whole, not a coverage, that is in force or in arrears.
     */
    public static List<RuleType> insurerScoped() {
        return List.of(POLICY_IN_FORCE, POLICY_STANDING);
    }


    /**
     * Checks whose FAIL is a warning for the analyst, not a rule that stopped the case: reports leave
     * them out of "which rules blocked the most cases", and the case detail shows them apart.
     */
    public static List<RuleType> advisoryRules() {
        return List.of(CLAIM_CAUSE_MATCH);
    }
}
