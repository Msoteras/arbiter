package ar.edu.utn.frba.arbiter.rules.dto;

import java.time.Instant;
import java.util.List;

/**
 * One change to the insurer's configuration, as the referente reads it in the history.
 *
 * <p>A row of {@code insurer_rule_history} is <b>not</b> a change: it's the version that stopped
 * being in force at {@code validTo}. The change is the pair — that version and the one that
 * replaced it, which is the next row's snapshot, or the live rule when there is no next row. This
 * record is that pair already resolved, so the view never has to reconstruct it.
 *
 * @param id              unique across both audit tables ({@code rule-12}, {@code scoring-3}) —
 *                        they have independent id sequences, so the raw id collides
 * @param branchId        null for a rule scoped to the whole insurer (Hard Stop, fraud record,
 *                        scoring); the view filters by it, the referente reads {@code branchName}
 * @param coverageName    null for anything not scoped to one coverage
 * @param previousValidFrom  when the replaced version had started being in force — with
 *                        {@code changedAt} it gives how long it lasted
 * @param changes         empty when the two versions are identical, which shouldn't happen but is
 *                        recorded honestly rather than hidden
 * @param current         whether the version this change introduced is still the one in force
 * @param partial         whether the stored version predates the audit recording the rule's on/off
 *                        state, so its {@code changes} can only cover the parameters. The view has
 *                        to say so: with these rows an empty {@code changes} means "not recorded",
 *                        not "nothing changed", and letting the referente read one as the other is
 *                        the kind of thing an audit trail exists to prevent
 */
public record RuleChangeEntry(
        String id,
        RuleChangeSource source,
        String ruleType,
        String ruleName,
        Long branchId,
        String branchName,
        Long coverageId,
        String coverageName,
        Instant changedAt,
        Instant previousValidFrom,
        String reason,
        List<RuleFieldChange> changes,
        boolean current,
        boolean partial) {
}
