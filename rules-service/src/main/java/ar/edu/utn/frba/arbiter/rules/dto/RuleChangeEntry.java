package ar.edu.utn.frba.arbiter.rules.dto;

import java.time.Instant;
import java.util.List;

/**
 * One change as the referente reads it. A history row is the version that ended, so a change pairs it
 * with the one that replaced it (the next row, or the live rule).
 *
 * @param id                unique across both audit tables ({@code rule-12}, {@code scoring-3})
 * @param branchId          null for insurer-wide rules
 * @param changedAt         for {@code CREATED}, when the first version took effect
 * @param previousValidFrom when the replaced version started; null on {@code CREATED}
 * @param changes           never empty on {@code UPDATED}, always empty on {@code CREATED}
 * @param current           whether it produced the version in force today
 * @param author            the referente's name, else the email in the reason; null if nobody is named
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
