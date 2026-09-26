package ar.edu.utn.frba.arbiter.common.enums;

import java.util.List;

/**
 * Shared by {@code insurer_rule.rule_type} (VARCHAR(20), hence {@link #POLICE_DEADLINE}) and
 * {@code rule_result.rule_type}.
 */
public enum RuleType {

    FAST_TRACK,

    /** Free-text exclusions and business rules go into the prompt; nobody evaluates them. */
    EXCLUSIONS,

    BUSINESS_RULES,

    /** A management target read by reports-service, not the legal deadline ({@code response_deadline}). */
    RESOLUTION_TARGET,

    COVERAGE_EXCLUSION,

    POLICY_IN_FORCE,

    WAITING_PERIOD,

    REPORT_DEADLINE,

    /** Threshold in {@code configuration}: the coverage's only deadline column is {@link #REPORT_DEADLINE}'s. */
    POLICE_DEADLINE,

    /** Over the trailing 12 months, per branch. */
    MAX_EVENTS_YEAR,

    /** Seeded inactive. No tiered arrears: no schema has an installment ledger. */
    POLICY_STANDING,

    /** Without an active row fraud records are ignored; {@code windowMonths} sets how long one counts. */
    FRAUD_RECORD,

    /**
     * Like {@link #CLAIM_EXHAUSTS_COVERAGE}, a {@code coverage} column with no rule row, hence the
     * nullable {@code rule_result.rule_id}.
     */
    COVERS_FAMILY_GROUP,

    /** Counted per policy, not per insured. */
    CLAIM_EXHAUSTS_COVERAGE,

    /** {@code FT_*} criteria carry no {@code rule_id}, so the threshold goes in {@code evaluated_value}. */
    FT_AMOUNT_RATIO,

    FT_PRIOR_CLAIMS,

    FT_POLICY_AGE,

    FT_POLICY_UP_TO_DATE,

    FT_REQUIRED_DOCS,

    /** Advisory, and the Fast Track path's only check of the narrative against the declared cause. */
    CLAIM_CAUSE_MATCH;

    /** {@link #COVERAGE_EXCLUSION} is left out: it is configured per coverage with its own selector. */
    public static List<RuleType> temporalRules() {
        return List.of(POLICY_IN_FORCE, WAITING_PERIOD, REPORT_DEADLINE, POLICE_DEADLINE,
                MAX_EVENTS_YEAR, POLICY_STANDING);
    }

    public static List<RuleType> coverageScoped() {
        return List.of(WAITING_PERIOD, REPORT_DEADLINE, POLICE_DEADLINE, MAX_EVENTS_YEAR);
    }

    /** Stored insurer-wide (null branch and coverage): the whole policy is in force or in arrears. */
    public static List<RuleType> insurerScoped() {
        return List.of(POLICY_IN_FORCE, POLICY_STANDING);
    }


    /** A FAIL warns the analyst but stops nothing. */
    public static List<RuleType> advisoryRules() {
        return List.of(CLAIM_CAUSE_MATCH);
    }
}
