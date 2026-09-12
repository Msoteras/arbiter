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
    private final BranchRepository branchRepository;

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
     * Re-reads a policy Arbiter already has and brings its local copy back in line with the
     * company's — the <b>cron</b> half of decision #10, which the on-demand import above never
     * covered: the sums are copied once, when the policy first enters, and nothing reads them
     * again. If the company later changes a sum insured, Arbiter keeps the old one forever, and
     * that number is the denominator of the Fast Track ratio and of the {@code amount_ratio}
     * scoring factor. It also happens to be what makes the analyst's screen disagree with the
     * engine, which is exactly what the audit has to be able to reconcile.
     *
     * <p><b>The company always wins.</b> The insurer DB is the source of truth for the contract
     * (CLAUDE.md decision #10); Arbiter's row is a copy, and a copy that argues is worse than no
     * copy.
     *
     * <p><b>Nothing is deleted.</b> A coverage the company stops returning is logged, not removed:
     * open cases point at it, and dropping it would leave them hanging off a coverage that no
     * longer exists. The same for a policy the company doesn't have any more.
     *
     * <p><b>{@code policy_snapshot} isn't touched.</b> That's the photo frozen when the claim was
     * classified — what the classification was actually evaluated against, and what the SSN
     * 2/2023 audit needs to stay immutable. Refreshing it would rewrite history.
     *
     * @return how many rows this policy changed, for the caller to log. Zero means the copy was
     *         already right, which is the expected answer on almost every run
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
                // The company added a coverage to the policy after it was synced. Same shape as the
                // import: without this row the wizard doesn't offer the claim causes it covers.
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
     * The tenant coverage this contracted risk points at, or null if the referente hasn't
     * configured it. Matched by name — {@code coverage.name} is unique per tenant and is the only
     * bridge, since the insurer DB doesn't know our ids — <b>and by branch</b>.
     *
     * <p>The branch check isn't paranoia: the insurer DB's coverage names are constrained to three
     * literals, so a Tecnología Portátil policy carries its theft cover under the name "Robo de
     * celular". Matching by name alone hangs a Celulares coverage — with its deadlines, its waiting
     * period and its events cap — off a laptop policy, and every rule downstream then evaluates
     * against the wrong contract.
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
        // With no branch resolved (the company didn't say, or the ramo isn't in the catalog) the
        // check doesn't run: skipping every coverage over a missing field would be worse than the
        // mismatch it guards against.
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
