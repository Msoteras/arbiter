package ar.edu.utn.frba.arbiter.common.enums;

import java.util.List;

/**
 * The rule vocabulary shared by {@code insurer_rule.rule_type} and {@code rule_result.rule_type}.
 * Lives in common-lib because it's shared: rules-service persists the literal on the rule row and
 * classification-service reads it to decide which evaluator applies. It used to be duplicated as
 * a private constant in four services, which is exactly how two modules drift out of sync.
 *
 * <p><b>Literals can't exceed 20 characters</b>: {@code insurer_rule.rule_type} is
 * {@code VARCHAR(20)} in {@code db/init-multitenant.sql} ({@code rule_result.rule_type} is 40).
 * That's why {@link #POLICE_DEADLINE} and not {@code POLICE_REPORT_DEADLINE}.
 *
 * <p><b>Being a row of {@code insurer_rule} and being auditable are two different things</b>, and
 * this enum spans both axes. Three families:
 * <ul>
 *   <li><b>Configuration</b> ({@link #FAST_TRACK}, {@link #EXCLUSIONS}, {@link #BUSINESS_RULES}) —
 *       the row holds parameters or free text and isn't "evaluated" against a claim, so it leaves
 *       no {@code rule_result} under its own type. What the Fast Track thresholds <i>do</i> leave
 *       is one row per criterion, under the {@code FT_*} types below.</li>
 *   <li><b>Hard evaluable rules</b> ({@link #COVERAGE_EXCLUSION} through
 *       {@link #CLAIM_EXHAUSTS_COVERAGE}) — the engine evaluates them by code against the claim and
 *       each evaluation leaves a {@code rule_result} row (Disposición SSN 2/2023). Most point at an
 *       {@code insurer_rule}; the two coverage-scope ones are {@code coverage} columns and carry no
 *       rule id. Failing one means the event isn't covered.</li>
 *   <li><b>Fast Track criteria</b> (the {@code FT_*} types) — evaluated and audited the same way,
 *       but they aren't hard rules: failing one only means the claim doesn't take the fast lane and
 *       goes to the LLM. Never {@code insurer_rule} rows.</li>
 * </ul>
 */
public enum RuleType {

    /** Fast Track gate thresholds, in the {@code configuration} JSONB. */
    FAST_TRACK(false),

    /** Branch-level free-text exclusions — go into the prompt, nobody evaluates them. */
    EXCLUSIONS(false),

    /** Branch-level free-text business rules — go into the prompt, nobody evaluates them. */
    BUSINESS_RULES(false),

    /** Blacklist of hechos generadores the coverage doesn't cover (D3). */
    COVERAGE_EXCLUSION(true),

    /** D13 · the event has to fall within the policy's coverage window. */
    POLICY_IN_FORCE(true),

    /** D9 · the event can't fall within the coverage's waiting period. */
    WAITING_PERIOD(true),

    /** D11 · deadline to report to the insurer, counted from the event. */
    REPORT_DEADLINE(true),

    /**
     * D12 · deadline to file the police report, counted from the event. The only hard rule whose
     * threshold lives in the {@code configuration} JSONB instead of a {@code coverage} column: the
     * coverage carries a single deadline ({@code report_deadline_hours}) and {@link #REPORT_DEADLINE}
     * already uses it, and that's a different deadline. Used to be the
     * {@code arbiter.rules.police-report-deadline-hours} property, fixed at 72h for every insurer.
     */
    POLICE_DEADLINE(true),

    /** D10 · cap on the insured's claims in the branch over the trailing 12 months. */
    MAX_EVENTS_YEAR(true),

    /**
     * Arrears ("mora"): the policy isn't up to date with its payments. Carries no threshold of its
     * own — the source data is {@code policy.upToDate()}, already supplied by the insurer DB — so
     * turning the rule on is enough. New as of 2026-08-13: arrears used to be evaluated only as an
     * <i>optional</i> Fast Track criterion ({@code requiresUpToDatePolicy}), which the referente
     * could leave unchecked, and a policy in arrears could still reach an LLM approval with nobody
     * auditing that. That's why this one seeds <b>inactive</b> (unlike the other five, which
     * preserve prior behavior): it's new behavior, and nobody flips it on except the referente.
     *
     * <p>Doesn't model the three tiers the actual business process uses (1 unpaid installment →
     * wait until next month; 2 unpaid with the event inside the unpaid billing period → reject; 3
     * unpaid → the certificate itself gets cancelled): those thresholds need an installment ledger
     * with period boundaries — installments don't run 1st-to-31st, they run from the policy's
     * anniversary date — which doesn't exist in any schema today. A separate story if the full
     * model is wanted.
     */
    POLICY_STANDING(true),

    /**
     * The insured has a fraud record from an earlier claim. Opt-in like every other hard rule: with
     * no active row the engine doesn't look at fraud records at all, neither to score nor to block.
     *
     * <p>It's the only evaluable rule that isn't about <i>this</i> claim, which is why it carries
     * its own parameter instead of reading a coverage column: {@code windowMonths}, how long a
     * record keeps counting. A fraud from six years ago can't weigh the same as one from last
     * year, and where that line falls is the insurer's call, not ours.
     */
    FRAUD_RECORD(true),

    /**
     * D9 · whether the coverage reaches the cohabiting family group or only the holder. Evaluated
     * by {@code CoverageScopeEvaluator} over the injured party the extraction read from the
     * documents.
     *
     * <p>Unlike every rule above, this one and {@link #CLAIM_EXHAUSTS_COVERAGE} are <b>not</b>
     * {@code insurer_rule} rows: they're {@code coverage} columns, edited in the Coberturas tab.
     * They have no rule id, which is why {@code rule_result.rule_id} is nullable — being auditable
     * and being a row of the rules table are two different things.
     */
    COVERS_FAMILY_GROUP(true),

    /**
     * D9 · whether a settled claim exhausts the coverage for the period. It's the fourth check the
     * insurer's own procedure spells out ("que las coberturas contratadas no se encuentren agotadas
     * por siniestros previos"). Counted per policy, not per insured. See
     * {@link #COVERS_FAMILY_GROUP} on why it carries no rule id.
     */
    CLAIM_EXHAUSTS_COVERAGE(true),

    /**
     * Fast Track gate · the claimed amount as a fraction of the coverage's sum insured, against the
     * referente's ceiling.
     *
     * <p>This one and the four below are the criteria {@code FastTrackValidator} compares, one
     * {@code rule_result} row each. They exist so the analyst can see <b>why</b> a claim took the
     * fast lane — or why it didn't — instead of a bare "Fast Track" label with nothing behind it:
     * the gate was already computing all of this and throwing it away.
     *
     * <p><b>They are not hard rules and must not be shown as such.</b> A failed criterion doesn't
     * say the event isn't covered; it says this claim goes to the LLM instead of the fast lane.
     *
     * <p>They carry no {@code rule_id}. The thresholds do live in a {@link #FAST_TRACK}
     * {@code insurer_rule} row, but its id doesn't travel: {@code /internal/fast-track} returns the
     * same config DTO the referente writes, which has no id. The threshold itself goes in
     * {@code evaluated_value}, so each row is auditable on its own.
     */
    FT_AMOUNT_RATIO(true),

    /** Fast Track gate · the insured's prior claims in the configured window, against the cap. */
    FT_PRIOR_CLAIMS(true),

    /** Fast Track gate · the policy's age at the time of the event, against the minimum. */
    FT_POLICY_AGE(true),

    /** Fast Track gate · whether the policy is up to date with its payments, when required. */
    FT_POLICY_UP_TO_DATE(true),

    /** Fast Track gate · whether the documents the gate requires are attached and readable. */
    FT_REQUIRED_DOCS(true);

    private final boolean evaluable;

    RuleType(boolean evaluable) {
        this.evaluable = evaluable;
    }

    /** Whether the engine evaluates it against a claim and leaves a trace in {@code rule_result}. */
    public boolean isEvaluable() {
        return evaluable;
    }

    /**
     * The hard rules {@code TemporalRuleEvaluator} evaluates (deadlines, coverage window, waiting
     * period, frequency, arrears) — i.e. the ones the referente turns on and off from the hard
     * rules tab. {@link #COVERAGE_EXCLUSION} is deliberately left out: it's also hard and
     * evaluable, but it's configured per coverage with its own hecho generador selector.
     *
     * <p>The union of {@link #coverageScoped()} and {@link #insurerScoped()} — the split that
     * matters for where the {@code insurer_rule} row lives, not for what the engine evaluates:
     * {@code TemporalRuleEvaluator} treats all six the same regardless of scope.
     */
    public static List<RuleType> temporalRules() {
        return List.of(POLICY_IN_FORCE, WAITING_PERIOD, REPORT_DEADLINE, POLICE_DEADLINE,
                MAX_EVENTS_YEAR, POLICY_STANDING);
    }

    /**
     * The temporal rules whose {@code insurer_rule} row is scoped to one (branch, coverage): their
     * threshold is a {@code coverage} column, so the switch travels with it. The referente's panel
     * edits these from each coverage's card.
     */
    public static List<RuleType> coverageScoped() {
        return List.of(WAITING_PERIOD, REPORT_DEADLINE, POLICE_DEADLINE, MAX_EVENTS_YEAR);
    }

    /**
     * The temporal rules scoped to the whole insurer instead ({@code branch_id} and
     * {@code coverage_id} both null): the policy itself, not a coverage within it, is what's in or
     * out of force, or in arrears. Confirmed against BBVA's real API — a claim gets rejected with
     * "Fecha de Ocurrencia de Siniestro Fuera de la Vigencia de la Operación Siniestrada" against
     * the policy as a whole, with no coverage in the request. The referente's panel edits these
     * from a general section, not from any one coverage's card.
     */
    public static List<RuleType> insurerScoped() {
        return List.of(POLICY_IN_FORCE, POLICY_STANDING);
    }

    /**
     * Every rule whose {@code insurer_rule} row is stored insurer-wide ({@code branch_id} and
     * {@code coverage_id} both null) — {@link #insurerScoped()} plus {@link #FRAUD_RECORD}. This is
     * the query the engine uses to pull them; {@code insurerScoped()} stays the pair the
     * referente's "reglas de la aseguradora" panel edits together, and the fraud record is kept out
     * of it because it's configured differently (a window, not an on-arrears mode) and isn't
     * temporal.
     */
    public static List<RuleType> insurerWide() {
        return List.of(POLICY_IN_FORCE, POLICY_STANDING, FRAUD_RECORD);
    }
}
