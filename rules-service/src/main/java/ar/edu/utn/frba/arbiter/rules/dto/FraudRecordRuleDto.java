package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * The insurer's policy on fraud records, as the referente edits it and as the classification
 * engine reads it: whether earlier fraud counts at all, for how long, and whether it disqualifies
 * a claim from Fast Track.
 *
 * <p>Stored as a single {@code insurer_rule} row of type {@code FRAUD_RECORD}, insurer-wide
 * ({@code branch_id} and {@code coverage_id} both null). With two branches there's no case for a
 * window per branch, and one number the referente can point at beats two that can disagree.
 *
 * @param ruleId          the {@code insurer_rule} id, so a fraud-record finding has something to
 *                        point {@code rule_result.rule_id} at. Null when the insurer never
 *                        configured the rule
 * @param enabled         {@code false} (or no row at all) ⇒ the engine ignores fraud records
 *                        entirely: they don't score and they don't block. They stay visible to the
 *                        analyst, which is the one thing that never depends on this rule
 * @param windowMonths    how long a record keeps counting, from the day it was registered
 * @param blocksFastTrack whether an in-force, expert-backed record disqualifies the claim from
 *                        Fast Track. A record is evidence about the person, not about this claim,
 *                        so making it veto is the insurer's call and not the engine's
 */
public record FraudRecordRuleDto(
        Long ruleId,
        boolean enabled,
        @Min(1) @Max(600) Integer windowMonths,
        boolean blocksFastTrack
) {

    /** Default window when the rule is turned on without one: five years. */
    public static final int DEFAULT_WINDOW_MONTHS = 60;

    /** How an insurer that never configured the rule behaves: fraud records exist, but don't count. */
    public static FraudRecordRuleDto disabled() {
        return new FraudRecordRuleDto(null, false, DEFAULT_WINDOW_MONTHS, false);
    }
}
