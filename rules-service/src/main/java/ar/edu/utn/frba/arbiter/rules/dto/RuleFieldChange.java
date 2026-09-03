package ar.edu.utn.frba.arbiter.rules.dto;

/**
 * One field that moved between two versions of a rule, already resolved to text.
 *
 * <p>The diff is computed here and not in the browser on purpose: the two versions are stored as
 * opaque JSONB whose shape depends on the rule type, and the frontend would have to learn every
 * one of those shapes to compare them. {@code field} is the JSON path ({@code deadlineHours},
 * {@code thresholds.maxAmount}); translating it to a label the referente reads is the frontend's
 * job, same as with every other enum literal in the platform.
 *
 * <p>{@code previousValue} or {@code newValue} is null when the field only exists on one side —
 * a parameter the rule didn't have before, or one that stopped applying.
 */
public record RuleFieldChange(String field, String previousValue, String newValue) {
}
