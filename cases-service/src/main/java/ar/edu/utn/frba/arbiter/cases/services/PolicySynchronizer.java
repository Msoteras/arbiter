package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyCoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
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
import java.util.Objects;

/**
 * Keeps the local policy snapshots in line with the insurer DB: on demand when a claim is filed
 * against a policy never synced, and on a schedule via {@link PolicyResyncScheduler}.
 *
 * <p>Every coverage the insurer returns becomes a {@link PolicyCoverage} row, since the wizard
 * offers claim causes based on the coverages on file. Coverages are matched to the tenant's
 * catalog, which holds referent configuration the insurer DB doesn't know; an unconfigured
 * coverage is never auto-created, because a coverage with no rules is worse than the error.
 */
@Service
@RequiredArgsConstructor
public class PolicySynchronizer {

    private static final Logger log = LoggerFactory.getLogger(PolicySynchronizer.class);

    private final InsurerAdapter insurerAdapter;
    private final PolicyRepository policyRepository;
    private final PolicyCoverageRepository policyCoverageRepository;
    private final CoverageRepository coverageRepository;
    private final BranchRepository branchRepository;

    /**
     * @param insuredId id of the tenant's {@code insured} who holds the policy
     * @throws UnresolvedCaseReferenceException 422, if the insurer doesn't have it either, or if
     *         none of its coverages is configured on this tenant
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
     * An unconfigured coverage is skipped with a warning rather than failing the import; it only
     * fails when none resolves, since then there is no contract to evaluate against.
     */
    private List<PolicyCoverage> importCoverages(PolicyResponse remote, Long policyId) {
        List<PolicyResponse.Coverage> remoteCoverages =
                remote.coverages() == null ? List.of() : remote.coverages();
        List<PolicyCoverage> saved = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        Long branchId = branchId(remote);
        int order = 1;
        for (PolicyResponse.Coverage remoteCoverage : remoteCoverages) {
            Coverage catalogued = resolve(remoteCoverage, branchId);
            if (catalogued == null) {
                unresolved.add(remoteCoverage.description());
                continue;
            }
            saved.add(policyCoverageRepository.save(PolicyCoverage.builder()
                    .policyId(policyId)
                    .coverage(catalogued)
                    .displayOrder(order++)
                    .sumInsured(sumInsured(remoteCoverage))
                    .deductiblePct(remoteCoverage.deductiblePct())
                    .build()));
        }
        if (!unresolved.isEmpty()) {
            log.warn("[PolicySynchronizer] Policy {}: {} coverage(s) not configured on this tenant, "
                    + "skipped: {}", remote.policyNumber(), unresolved.size(), unresolved);
        }
        if (saved.isEmpty()) {
            // Names the coverages when there are any: what's missing is their configuration.
            throw unresolved.isEmpty()
                    ? new UnresolvedCaseReferenceException("coverage for policy", remote.policyNumber())
                    : new UnresolvedCaseReferenceException("coverage", String.join(", ", unresolved));
        }
        return saved;
    }

    /**
     * Realigns the local copy with the insurer, which always wins. Nothing is deleted: open cases
     * point at coverages and policies the insurer may no longer return. {@code policy_snapshot} is
     * never touched — it is the immutable record of what a classification was evaluated against.
     *
     * @return how many rows changed; zero is the expected answer on almost every run
     */
    @Transactional
    public int resync(Policy local) {
        PolicyResponse remote = insurerAdapter.findPolicy(local.getExternalPolicyNumber()).orElse(null);
        if (remote == null) {
            log.warn("[PolicySynchronizer] Policy {} is no longer in the insurer DB — local copy left "
                    + "as is (cases point at it)", local.getExternalPolicyNumber());
            return 0;
        }

        int changes = 0;
        boolean inForce = inForceToday(remote);
        if (!Objects.equals(local.getProduct(), remote.product()) || local.isInForce() != inForce) {
            local.setProduct(remote.product());
            local.setInForce(inForce);
            changes++;
        }
        local.setSyncedAt(Instant.now());
        policyRepository.save(local);

        return changes + resyncCoverages(local, remote);
    }

    private int resyncCoverages(Policy local, PolicyResponse remote) {
        List<PolicyResponse.Coverage> remoteCoverages =
                remote.coverages() == null ? List.of() : remote.coverages();
        Long branchId = branchId(remote);
        List<String> unresolved = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        int changes = 0;
        int order = 1;

        for (PolicyResponse.Coverage remoteCoverage : remoteCoverages) {
            Coverage catalogued = resolve(remoteCoverage, branchId);
            if (catalogued == null) {
                unresolved.add(remoteCoverage.description());
                continue;
            }
            seen.add(catalogued.getId());
            int position = order++;
            PolicyCoverage contracted = policyCoverageRepository
                    .findByPolicyIdAndCoverageId(local.getId(), catalogued.getId())
                    .orElse(null);

            if (contracted == null) {
                // Added by the insurer after the first sync.
                policyCoverageRepository.save(PolicyCoverage.builder()
                        .policyId(local.getId())
                        .coverage(catalogued)
                        .displayOrder(position)
                        .sumInsured(sumInsured(remoteCoverage))
                        .deductiblePct(remoteCoverage.deductiblePct())
                        .build());
                log.info("[PolicySynchronizer] Policy {}: coverage '{}' added from the insurer DB",
                        local.getExternalPolicyNumber(), catalogued.getName());
                changes++;
                continue;
            }

            BigDecimal sum = sumInsured(remoteCoverage);
            boolean differs = contracted.getSumInsured().compareTo(sum) != 0
                    || !samePercentage(contracted.getDeductiblePct(), remoteCoverage.deductiblePct())
                    || !Objects.equals(contracted.getDisplayOrder(), position);
            if (differs) {
                log.info("[PolicySynchronizer] Policy {}: coverage '{}' realigned — sum insured {} → {}",
                        local.getExternalPolicyNumber(), catalogued.getName(),
                        contracted.getSumInsured(), sum);
                contracted.setSumInsured(sum);
                contracted.setDeductiblePct(remoteCoverage.deductiblePct());
                contracted.setDisplayOrder(position);
                policyCoverageRepository.save(contracted);
                changes++;
            }
        }

        List<String> dropped = policyCoverageRepository.findByPolicyIdOrderByDisplayOrderAsc(local.getId())
                .stream()
                .filter(pc -> !seen.contains(pc.getCoverage().getId()))
                .map(pc -> pc.getCoverage().getName())
                .toList();
        if (!dropped.isEmpty()) {
            log.warn("[PolicySynchronizer] Policy {}: {} local coverage(s) the company no longer "
                    + "returns, kept on purpose: {}", local.getExternalPolicyNumber(), dropped.size(), dropped);
        }
        if (!unresolved.isEmpty()) {
            log.warn("[PolicySynchronizer] Policy {}: {} coverage(s) not configured on this tenant "
                    + "for its branch, skipped: {}", local.getExternalPolicyNumber(),
                    unresolved.size(), unresolved);
        }
        return changes;
    }

    /**
     * Null if the referent hasn't configured it. Matched by name and by branch: insurer coverage
     * names are shared across branches (a laptop policy's theft cover is also named "Robo de
     * celular"), so name alone could attach the wrong branch's coverage and rules.
     */
    private Coverage resolve(PolicyResponse.Coverage remoteCoverage, Long branchId) {
        String name = remoteCoverage.description();
        if (name == null) {
            return null;
        }
        Coverage catalogued = coverageRepository.findByName(name).orElse(null);
        if (catalogued == null) {
            return null;
        }
        // With no branch resolved the check is skipped rather than dropping every coverage.
        return branchId == null || branchId.equals(catalogued.getBranchId()) ? catalogued : null;
    }

    private Long branchId(PolicyResponse remote) {
        return remote.branch() == null
                ? null
                : branchRepository.findByName(remote.branch()).map(Branch::getId).orElse(null);
    }

    /** No sum insured is a zero and not a null: the column is NOT NULL and the rules divide by it. */
    private BigDecimal sumInsured(PolicyResponse.Coverage remoteCoverage) {
        return remoteCoverage.insuredAmount() == null ? BigDecimal.ZERO : remoteCoverage.insuredAmount();
    }

    /** {@code compareTo} and not {@code equals}: 10.00 and 10.0 are the same percentage. */
    private boolean samePercentage(BigDecimal local, BigDecimal remote) {
        return local == null || remote == null
                ? local == remote
                : local.compareTo(remote) == 0;
    }

    /**
     * "In force today" at sync time, not on the event date (see {@code PolicyEligibilityValidator}).
     * Missing dates count as in force rather than asserting something unverifiable.
     */
    private boolean inForceToday(PolicyResponse remote) {
        LocalDateTime now = LocalDateTime.now();
        return (remote.effectiveFrom() == null || !now.isBefore(remote.effectiveFrom()))
                && (remote.effectiveTo() == null || !now.isAfter(remote.effectiveTo()));
    }
}
