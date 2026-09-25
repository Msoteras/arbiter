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
 * @param changedAt       when the change was saved; for a {@code CREATED} entry, when the rule's
 *                        first version took effect
 * @param previousValidFrom  when the replaced version had started being in force — with
 *                        {@code changedAt} it gives how long it lasted; null on {@code CREATED}
 * @param changes         never empty on {@code UPDATED} (saves that changed nothing aren't
 *                        entries); always empty on {@code CREATED}
 * @param current         whether this entry produced the version in force today: the newest one
 *                        of its rule
 * @param author          who made the change: the referente's name, or the email recorded in
 *                        {@code reason} when there is no profile to name them by; null if the
 *                        reason names nobody; on {@code CREATED}, the user who created the rule,
 *                        by name if they have a referente profile, by email otherwise
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
        RuleChangeKind kind,
        String author) {
}
