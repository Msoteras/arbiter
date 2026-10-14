package ar.edu.utn.frba.arbiter.common.dto;

import java.util.List;

/**
 * What cases-service knows about an insurer's setup, for rules-service's checklist.
 *
 * @param branchIdsWithPolicies     branches with at least one coverage on the insurer's policies.
 *                                  Only those get a coverages step: a branch without policies can't
 *                                  receive a claim
 * @param activeProviders           external providers marked active
 * @param branchesWithSettlementCap branches with a settlement authority row
 */
public record OnboardingCaseCounts(
        List<Long> branchIdsWithPolicies,
        int activeProviders,
        int branchesWithSettlementCap
) {}
