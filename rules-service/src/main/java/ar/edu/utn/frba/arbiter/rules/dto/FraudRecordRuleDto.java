package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * The insurer's policy on fraud records, as the referente edits it and as the classification
 * engine reads it: how long an earlier fraud keeps counting, and whether it disqualifies a claim
 * from Fast Track.
 *
 * <p><b>Whether the record scores is not here.</b> That lives in the scoring config, with every
 * other factor ({@code fraud_history} and its weight). A second switch for the same thing let the
 * referente's panel claim the record was scoring while the scoring config didn't list the factor.
 *
 * <p>Stored as a single {@code insurer_rule} row of type {@code FRAUD_RECORD}, insurer-wide
 * ({@code branch_id} and {@code coverage_id} both null). With two branches there's no case for a
 * window per branch, and one number the referente can point at beats two that can disagree.
 *
 * @param ruleId          the {@code insurer_rule} id, so a fraud-record finding has something to
 *                        point {@code rule_result.rule_id} at. Null when the insurer never
 *                        configured the rule, and then nothing vetoes Fast Track
 * @param windowMonths    how long a record keeps counting, from the day it was registered
 * @param blocksFastTrack whether an in-force, expert-backed record disqualifies the claim from
 *                        Fast Track. A record is evidence about the person, not about this claim,
 *                        so making it veto is the insurer's call and not the engine's
 */
public record FraudRecordRuleDto(
        Long ruleId,
        @Min(1) @Max(600) Integer windowMonths,
        boolean blocksFastTrack
) {

    /** Window applied when the insurer never set one: three years. */
    public static final int DEFAULT_WINDOW_MONTHS = 36;

    /** How an insurer that never configured the rule behaves: default window, nothing vetoed. */
    public static FraudRecordRuleDto unconfigured() {
        return new FraudRecordRuleDto(null, DEFAULT_WINDOW_MONTHS, false);
    }
}
