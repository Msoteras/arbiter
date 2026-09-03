package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyCoverageRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which of a policy's coverages answers for a given hecho generador.
 *
 * <p>The question only exists because a policy has several: a phone policy covers robo <i>and</i>
 * hurto, each with its own sum insured, deductible, waiting period and reporting deadline. Picking
 * the wrong one doesn't just get the numbers wrong — it decides whether the event is covered at
 * all. Before {@code policy_coverage} the case simply inherited the policy's single coverage, so
 * a hurto filed on a policy that covers hurto was evaluated against the robo coverage.
 *
 * <p><b>How the link is established.</b> Through the {@code COVERAGE_EXCLUSION} rule the referente
 * administers on the Coberturas tab ("Hechos generadores NO cubiertos"): the list of causes each
 * coverage does <i>not</i> cover. A coverage answers for a cause when that cause is not on its list.
 * With the lists filled in, exactly one coverage of a policy answers for each cause of its branch.
 *
 * <p><b>What happens if the referente leaves a coverage without its list.</b> That coverage covers
 * everything, so more than one may qualify and this falls back to the company's own ordering
 * ({@code display_order}, {@code cobertura1..cobertura20} in the source). It is the same coverage
 * the old single-coverage code would have used, so an unconfigured tenant is no worse off than
 * before — but the answer is only as precise as the exclusions the referente loaded. The seeded
 * tenants have them for every coverage.
 */
@Service
@RequiredArgsConstructor
public class PolicyCoverageResolver {

    private static final Logger log = LoggerFactory.getLogger(PolicyCoverageResolver.class);

    private final PolicyCoverageRepository policyCoverageRepository;
    private final RulesServiceClient rulesServiceClient;

    /** Everything the policy contracted, in the company's order. Never empty for a synced policy. */
    public List<PolicyCoverage> contractedCoverages(Long policyId) {
        return policyCoverageRepository.findByPolicyIdOrderByDisplayOrderAsc(policyId);
    }

    /**
     * The coverage that answers for {@code claimCauseId}, or the first contracted one when the
     * cause is unknown (the eligibility precheck runs before the wizard asks "¿qué te pasó?").
     *
     * @throws UnresolvedCaseReferenceException 422 when the policy has no coverage on file at all —
     *         a policy that was never synced, or whose coverages the referente hasn't configured
     */
    public PolicyCoverage resolveFor(Long policyId, Long claimCauseId) {
        List<PolicyCoverage> contracted = contractedCoverages(policyId);
        if (contracted.isEmpty()) {
            throw new UnresolvedCaseReferenceException("coverage for policy", String.valueOf(policyId));
        }
        if (claimCauseId == null) {
            return contracted.getFirst();
        }

        List<PolicyCoverage> candidates = contracted.stream()
                .filter(pc -> !excludes(pc, claimCauseId))
                .toList();
        if (candidates.isEmpty()) {
            // Every coverage of the policy blacklists this cause. Not an error here: the case is
            // created and PolicyEligibilityValidator is the one that rejects it with the reason,
            // so the insured gets "no está cubierto" and not an opaque 422 about coverages.
            log.info("[PolicyCoverageResolver] Policy {}: no contracted coverage covers claim cause {} — "
                    + "falling back to the first for the eligibility check to report it", policyId, claimCauseId);
            return contracted.getFirst();
        }
        if (candidates.size() > 1) {
            log.debug("[PolicyCoverageResolver] Policy {}: {} coverages could answer for claim cause {}; "
                            + "taking the company's first. Configure the coverage exclusions to disambiguate.",
                    policyId, candidates.size(), claimCauseId);
        }
        return candidates.getFirst();
    }

    /**
     * The claim causes the policy covers under <b>any</b> of its coverages — what the wizard offers.
     * A cause is offered when at least one contracted coverage doesn't blacklist it, so the answer
     * is the intersection of the blacklists: excluding a cause takes every coverage saying no.
     *
     * <p>This is the bug the single-coverage model produced most visibly: the wizard filtered by
     * the policy's one coverage, so the holder of a policy covering robo and hurto was never
     * offered hurto.
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
