package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyCoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
 * <p><b>What can genuinely be missing is the coverage.</b> Each contracted coverage points at a
 * tenant {@code coverage}, which is referente configuration (deadlines, waiting period, events cap)
 * and not something the insurer DB knows — from there only name, insured amount and deductible come
 * in. If none of the policy's coverages is configured on this tenant, this fails naming them and
 * not the policy: the referente has to create them, and creating one here on its own would leave a
 * coverage with no rule at all, which is worse than the error.
 *
 * <p><b>All of them, not the first one.</b> This used to keep {@code coverages.get(0)} because
 * {@code policy} carried a single {@code coverage_id}. A real policy covers several risks — robo
 * and hurto on the same phone, each with its own sum insured — so every one the company returns
 * becomes a {@link PolicyCoverage} row. Dropping the rest meant the insured couldn't file for a
 * cause their contract covers: the wizard filters the claim causes by the coverages on file.
 */
@Service
@RequiredArgsConstructor
public class PolicySynchronizer {

    private static final Logger log = LoggerFactory.getLogger(PolicySynchronizer.class);

    private final InsurerAdapter insurerAdapter;
    private final PolicyRepository policyRepository;
    private final PolicyCoverageRepository policyCoverageRepository;
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
                .inForce(inForceToday(remote))
                .syncedAt(Instant.now())
                .insuredId(insuredId)
                .build());

        List<PolicyCoverage> contracted = importCoverages(remote, snapshot.getId());
        log.info("[PolicySynchronizer] On-demand snapshot created for policy {} ({} coverage(s): {})",
                policyNumber, contracted.size(),
                contracted.stream().map(pc -> pc.getCoverage().getName()).toList());
        return snapshot;
    }

    /**
     * Every coverage the company returns, matched by name against the tenant's catalog — the only
     * possible bridge, since the insurer DB doesn't know our ids.
     *
     * <p>A coverage the referente hasn't configured is skipped with a warning rather than failing
     * the whole import: one unconfigured risk shouldn't block filing against the ones that are
     * configured. It only fails when <b>none</b> of them resolves, because then there is no
     * contract to evaluate anything against.
     */
    private List<PolicyCoverage> importCoverages(PolicyResponse remote, Long policyId) {
        List<PolicyResponse.Coverage> remoteCoverages =
                remote.coverages() == null ? List.of() : remote.coverages();
        List<PolicyCoverage> saved = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        int order = 1;
        for (PolicyResponse.Coverage remoteCoverage : remoteCoverages) {
            String name = remoteCoverage.description();
            Coverage catalogued = name == null ? null : coverageRepository.findByName(name).orElse(null);
            if (catalogued == null) {
                unresolved.add(name);
                continue;
            }
            saved.add(policyCoverageRepository.save(PolicyCoverage.builder()
                    .policyId(policyId)
                    .coverage(catalogued)
                    .displayOrder(order++)
                    .sumInsured(remoteCoverage.insuredAmount() == null
                            ? BigDecimal.ZERO : remoteCoverage.insuredAmount())
                    .deductiblePct(remoteCoverage.deductiblePct())
                    .build()));
        }
        if (!unresolved.isEmpty()) {
            log.warn("[PolicySynchronizer] Policy {}: {} coverage(s) not configured on this tenant, "
                    + "skipped: {}", remote.policyNumber(), unresolved.size(), unresolved);
        }
        if (saved.isEmpty()) {
            // Naming the coverages and not the policy: the policy is there, what's missing is the
            // referente's configuration for the risks it covers. With no coverages at all in the
            // answer there's nothing to name, so the policy is the best pointer left.
            throw unresolved.isEmpty()
                    ? new UnresolvedCaseReferenceException("coverage for policy", remote.policyNumber())
                    : new UnresolvedCaseReferenceException("coverage", String.join(", ", unresolved));
        }
        return saved;
    }

    /**
     * {@code in_force} is "in force today", the snapshot at sync time — different from being in
     * force on the event date, which gets evaluated against the company's dates and not this
     * column (see {@code PolicyEligibilityValidator}). With no dates it's assumed in force: the
     * policy came from the company, and marking it not in force over missing data would be
     * asserting something that couldn't be verified.
     */
    private boolean inForceToday(PolicyResponse remote) {
        LocalDateTime now = LocalDateTime.now();
        return (remote.effectiveFrom() == null || !now.isBefore(remote.effectiveFrom()))
                && (remote.effectiveTo() == null || !now.isAfter(remote.effectiveTo()));
    }
}
