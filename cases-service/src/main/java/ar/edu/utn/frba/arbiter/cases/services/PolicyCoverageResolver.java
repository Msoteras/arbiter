package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyCoverageRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which of a policy's coverages answers for a given claim cause. Each coverage has its own sum
 * insured, deductible, waiting period and reporting deadline, so picking the wrong one can decide
 * whether the event is covered at all.
 *
 * <p>A coverage answers for a cause when the cause is not on its {@code COVERAGE_EXCLUSION} list.
 * A coverage with no list covers everything, so ties fall back to the insurer's
 * {@code display_order}: the answer is only as precise as the exclusions the referent loaded.
 */
@Service
@RequiredArgsConstructor
public class PolicyCoverageResolver {

    private static final Logger log = LoggerFactory.getLogger(PolicyCoverageResolver.class);

    private final PolicyCoverageRepository policyCoverageRepository;
    private final RulesServiceClient rulesServiceClient;
    private final ClaimCauseRepository claimCauseRepository;

    /** Everything the policy contracted, in the company's order. Never empty for a synced policy. */
    public List<PolicyCoverage> contractedCoverages(Long policyId) {
        return policyCoverageRepository.findByPolicyIdOrderByDisplayOrderAsc(policyId);
    }

    /**
     * The coverage that answers for {@code claimCauseId}, or the first contracted one when the
     * cause is unknown (the eligibility precheck runs before the wizard asks for the cause).
     *
     * @throws UnresolvedCaseReferenceException 422 when the policy has no coverage on file at all
     */
    public PolicyCoverage resolveFor(Long policyId, Long claimCauseId) {
        List<PolicyCoverage> contracted = contractedCoverages(policyId);
        if (contracted.isEmpty()) {
            throw new UnresolvedCaseReferenceException("coverage for policy", String.valueOf(policyId));
        }
        if (claimCauseId == null) {
            return contracted.getFirst();
        }

        // A coverage of another branch never answers for this cause: with an empty exclusion list
        // it would otherwise read as "covers everything" and win by display_order alone.
        Long causeBranchId = claimCauseRepository.findById(claimCauseId)
                .map(cc -> cc.getBranch().getId())
                .orElse(null);
        List<PolicyCoverage> sameBranch = causeBranchId == null
                ? contracted
                : contracted.stream().filter(pc -> causeBranchId.equals(pc.getCoverage().getBranchId())).toList();
        if (sameBranch.isEmpty()) {
            log.warn("[PolicyCoverageResolver] Policy {}: none of its {} contracted coverage(s) belong to "
                            + "claim cause {}'s branch — a synced policy_coverage row is likely pointing at the "
                            + "wrong branch's coverage. Falling back to the first for the eligibility check to report it.",
                    policyId, contracted.size(), claimCauseId);
            return contracted.getFirst();
        }

        List<PolicyCoverage> candidates = sameBranch.stream()
                .filter(pc -> !excludes(pc, claimCauseId))
                .toList();
        if (candidates.isEmpty()) {
            // Not an error here: PolicyEligibilityValidator rejects it with a reason the insured
            // understands, instead of an opaque 422 about coverages.
            log.info("[PolicyCoverageResolver] Policy {}: no contracted coverage covers claim cause {} — "
                    + "falling back to the first for the eligibility check to report it", policyId, claimCauseId);
            return sameBranch.getFirst();
        }
        if (candidates.size() > 1) {
            log.debug("[PolicyCoverageResolver] Policy {}: {} coverages could answer for claim cause {}; "
                            + "taking the company's first. Configure the coverage exclusions to disambiguate.",
                    policyId, candidates.size(), claimCauseId);
        }
        return candidates.getFirst();
    }

    /**
     * The intersection of the coverages' exclusion lists: a cause is offered by the wizard as long
     * as at least one contracted coverage doesn't exclude it.
     */
    public Set<Long> excludedClaimCauseIds(Long policyId) {
        List<PolicyCoverage> contracted = contractedCoverages(policyId);
        if (contracted.isEmpty()) {
            return Set.of();
        }
        Set<Long> intersection = null;
        for (PolicyCoverage pc : contracted) {
            Set<Long> excluded = new LinkedHashSet<>(
                    rulesServiceClient.excludedClaimCauseIds(pc.getCoverage().getId()));
            if (intersection == null) {
                intersection = excluded;
            } else {
                intersection.retainAll(excluded);
            }
            if (intersection.isEmpty()) {
                return Set.of();
            }
        }
        return intersection == null ? Set.of() : intersection;
    }

    private boolean excludes(PolicyCoverage contracted, Long claimCauseId) {
        return rulesServiceClient.excludedClaimCauseIds(contracted.getCoverage().getId())
                .contains(claimCauseId);
    }
}
