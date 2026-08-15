package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Pulls from the insurer DB a policy Arbiter doesn't have yet and persists it as a local snapshot
 * (decision #10: *"Arbiter persists local snapshots of what the insurer DB hands it… a cron or an
 * on-demand query pulls the data and maps it to its own entities"*). This is the **on-demand**
 * half: it fires when someone files a claim against a policy that exists at the company but was
 * never synced.
 *
 * <p>That case used to end in a 422 "no policy matching", which was misleading: the policy exists
 * and the insured is looking at it in the portal — the listing reads the insurer DB live — so the
 * message claimed something didn't exist that the screen had just offered them. What was missing
 * wasn't the data, it was the sync.
 *
 * <p><b>What can genuinely be missing is the coverage.</b> {@code policy.coverage_id} is NOT NULL
 * and points at a tenant {@code coverage}, which is referente configuration (deadlines, waiting
 * period, events cap) and not something the insurer DB knows — from there only name, insured
 * amount and deductible come in. If the policy's branch has no coverage created yet, this fails
 * naming the coverage and not the policy — the referente has to create it, and creating one here
 * on its own would leave a coverage with no rule at all, which is worse than the error.
 */
@Service
@RequiredArgsConstructor
public class PolicySynchronizer {

    private static final Logger log = LoggerFactory.getLogger(PolicySynchronizer.class);

    private final InsurerAdapter insurerAdapter;
    private final PolicyRepository policyRepository;
    private final CoverageRepository coverageRepository;

    /**
     * @param insuredId id of the tenant's {@code insured} who holds the policy
     * @return the newly created snapshot
     * @throws UnresolvedCaseReferenceException 422, if the company doesn't have it either, or if
     *         its coverage isn't configured on this tenant
     */
    @Transactional
    public Policy importFromInsurer(String policyNumber, Long insuredId) {
        PolicyResponse remote = insurerAdapter.findPolicy(policyNumber)
                .orElseThrow(() -> new UnresolvedCaseReferenceException("policy", policyNumber));

        Policy snapshot = policyRepository.save(Policy.builder()
                .externalPolicyNumber(remote.policyNumber())
                .product(remote.product())
                .sumInsured(remote.insuredAmount() == null ? BigDecimal.ZERO : remote.insuredAmount())
                .inForce(inForceToday(remote))
                .syncedAt(Instant.now())
                .insuredId(insuredId)
                .coverage(resolveCoverage(remote))
                .build());

        log.info("[PolicySynchronizer] On-demand snapshot created for policy {} (coverage '{}')",
                policyNumber, snapshot.getCoverage().getName());
        return snapshot;
    }

    /**
     * The policy's primary coverage (the first one the company returns), matched by name against
     * the tenant's. It's the only possible bridge: the insurer DB doesn't know our ids.
     */
    private Coverage resolveCoverage(PolicyResponse remote) {
        String name = remote.coverages() == null || remote.coverages().isEmpty()
                ? null
                : remote.coverages().get(0).description();
        if (name == null) {
            throw new UnresolvedCaseReferenceException("coverage for policy", remote.policyNumber());
        }
        return coverageRepository.findByName(name)
                .orElseThrow(() -> new UnresolvedCaseReferenceException("coverage", name));
    }

    /**
     * {@code in_force} is "in force today", the snapshot at sync time — different from being in
     * force on the event date, which gets evaluated against the company's dates and not this
     * column (see {@code PolicyEligibilityValidator}). With no dates it's assumed in force: the
     * policy came from the company, and marking it not in force over missing data would be
     * asserting something that couldn't be verified.
     */
    private boolean inForceToday(PolicyResponse remote) {
        LocalDate today = LocalDate.now();
        return (remote.effectiveFrom() == null || !today.isBefore(remote.effectiveFrom()))
                && (remote.effectiveTo() == null || !today.isAfter(remote.effectiveTo()));
    }
}
