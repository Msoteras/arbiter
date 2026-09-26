package ar.edu.utn.frba.arbiter.rules.dto;

import java.time.Instant;
import java.util.List;

/**
 * A history row is the version that ended, so a change pairs it with its successor (next row or live
 * rule).
 *
 * @param id      unique across both audit tables ({@code rule-12}, {@code scoring-3})
 * @param changes never empty on {@code UPDATED}, always empty on {@code CREATED}
 * @param author  the referente's name, else the email in the reason; null if nobody is named
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
