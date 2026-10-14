package ar.edu.utn.frba.arbiter.rules.dto;

import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;

/**
 * One checklist entry. The front builds the text; the backend only sends numbers.
 *
 * @param branchId       for a per-branch step; null otherwise
 * @param branchName     for a per-branch step; null otherwise
 * @param count          what the step's summary shows: coverages of the branch, analysts, insured
 *                       with an account, claim causes with documents, active scoring factors,
 *                       coverages with Fast Track, active providers, branches with a cap, active
 *                       Hard Stop rules, fraud window in months, target days, common exclusions or
 *                       business rules
 * @param secondaryCount analysts without an activated account; null for every other step
 */
public record OnboardingStepDto(
        OnboardingStep step,
        OnboardingStep.Level level,
        Long branchId,
        String branchName,
        Status status,
        Integer count,
        Integer secondaryCount
) {
    public enum Status { PENDING, DONE, SKIPPED }
}
