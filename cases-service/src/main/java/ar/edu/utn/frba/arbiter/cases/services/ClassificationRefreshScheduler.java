package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.exceptions.RulesUnavailableException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationFailureReason;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Two cross-tenant background sweeps over cases waiting on classification:
 * <ul>
 *   <li>{@link #refreshPendingCases()} is the safety net for notices from classification-service that
 *       never arrived: it polls for {@code PENDING_CLASSIFICATION} results and gives up to
 *       {@code CLASSIFICATION_FAILED} once a case has waited {@code give-up-after}.</li>
 *   <li>{@link #recoverInfrastructureFailures()} requeues {@code CLASSIFICATION_FAILED} cases whose
 *       recorded reason is {@link ClassificationFailureReason#INFRASTRUCTURE}; polling alone never
 *       re-triggers a run, so they would otherwise stay failed after the outage is over.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ClassificationRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClassificationRefreshScheduler.class);

    private final CaseRepository caseRepository;
    private final CaseDocumentRepository caseDocumentRepository;
    private final CaseStatusService caseStatusService;
    private final ClaimsAnalysisClient claimsAnalysisClient;
    private final ClassificationOutcomeService classificationOutcomeService;
    private final PolicyCoverageResolver policyCoverageResolver;
    private final InsurerRepository insurerRepository;
    private final Clock clock;

    @Value("${arbiter.classification-refresh.give-up-after:3h}")
    private Duration giveUpAfter;

    @Value("${arbiter.classification-refresh.interval-ms:600000}")
    private long intervalMs;

    @Value("${arbiter.classification-refresh.requeue-cooldown:30m}")
    private Duration requeueCooldown;

    @PostConstruct
    void logWindow() {
        log.info("[Refresh] Polling every {} s; giving up after {} min without a result",
                intervalMs / 1000, giveUpAfter.toMinutes());
    }

    @Scheduled(fixedDelayString = "${arbiter.classification-refresh.interval-ms:600000}")
    public void refreshPendingCases() {
        for (Insurer insurer : insurerRepository.findByActiveTrue()) {
            try {
                TenantContext.set(insurer.getSchemaName());
                refreshPendingCasesForCurrentTenant();
            } catch (Exception e) {
                // One insurer's failure must not stop the sweep for the rest.
                log.warn("Refresh sweep failed for insurer {} ({}): {}",
                        insurer.getName(), insurer.getSchemaName(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void refreshPendingCasesForCurrentTenant() {
        List<Case> pending = caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION);
        if (pending.isEmpty()) {
            return;
        }
        log.debug("Refreshing {} pending case(s) in {}", pending.size(), TenantContext.get());
        for (Case caseRecord : pending) {
            try {
                boolean resolved = claimsAnalysisClient.refreshClassification(caseRecord);
                if (!resolved) {
                    incrementAttempts(caseRecord);
                }
            } catch (Exception e) {
                log.warn("Refresh failed for case {}: {}", caseRecord.getId(), e.getMessage());
                incrementAttempts(caseRecord);
            }
        }
    }

    /**
     * The counter is written with a targeted conditional update, never {@code save(caseRecord)}:
     * saving the sweep's stale copy would silently revert changes made in between (such as an
     * analyst's retry). Advancing the counter also acts as the lock when several instances sweep
     * the same schema — whoever loses the compare-and-set backs off.
     */
    private void incrementAttempts(Case caseRecord) {
        int previous = caseRecord.getClassificationAttempts();
        int attempts = previous + 1;

        if (caseRepository.advanceClassificationAttempts(caseRecord.getId(), previous, attempts) == 0) {
            log.debug("Case {} already advanced by another sweep, skipping", caseRecord.getId());
            return;
        }

        Duration waited = Duration.between(caseStatusService.enteredCurrentStatusAt(caseRecord), clock.instant());
        if (waited.compareTo(giveUpAfter) < 0) {
            return;
        }

        classificationOutcomeService.markFailed(caseRecord,
                "clasificación fallida: sin resultado después de " + describe(giveUpAfter));
    }

    private static String describe(Duration duration) {
        long minutes = duration.toMinutes();
        return minutes % 60 == 0 ? minutes / 60 + " h" : minutes + " min";
    }

    /**
     * Not right after a failure: classification-service already retries for several minutes before
     * a case fails, and requeuing at once would just hammer a dependency that's still down.
     */
    @Scheduled(fixedDelayString = "${arbiter.classification-refresh.recovery-interval-ms:300000}")
    public void recoverInfrastructureFailures() {
        for (Insurer insurer : insurerRepository.findByActiveTrue()) {
            try {
                TenantContext.set(insurer.getSchemaName());
                recoverInfrastructureFailuresForCurrentTenant();
            } catch (Exception e) {
                // One insurer's failure must not stop the sweep for the rest.
                log.warn("Infrastructure-failure recovery sweep failed for insurer {} ({}): {}",
                        insurer.getName(), insurer.getSchemaName(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void recoverInfrastructureFailuresForCurrentTenant() {
        List<Case> failed = caseRepository.findFailedByReason(ClassificationFailureReason.INFRASTRUCTURE).stream()
                .filter(this::requeueCooldownElapsed)
                .toList();
        if (failed.isEmpty()) {
            return;
        }
        // Otherwise each sweep during an outage bounces every case to pending and back, two history rows each.
        if (!claimsAnalysisClient.isReachable()) {
            log.info("classification-service still unreachable; {} case(s) in {} wait for the next sweep",
                    failed.size(), TenantContext.get());
            return;
        }
        log.info("Requeuing {} CLASSIFICATION_FAILED case(s) in {} after an infrastructure failure",
                failed.size(), TenantContext.get());
        for (Case caseRecord : failed) {
            try {
                requeueAfterInfrastructureFailure(caseRecord.getId(), policyCoverageResolver.reverifiedCoverage(caseRecord));
            } catch (RulesUnavailableException e) {
                log.info("Case {} waits for rules-service to verify its coverage before being requeued",
                        caseRecord.getId());
            } catch (Exception e) {
                log.warn("Could not requeue case {}: {}", caseRecord.getId(), e.getMessage());
            }
        }
    }

    /**
     * Claims the case with a compare-and-set, since another instance may be sweeping too, then
     * re-reads it: the CAS checks the reason but not the status, and an analyst may have retried
     * the case in between.
     */
    private void requeueAfterInfrastructureFailure(Long caseId, Optional<Coverage> verifiedCoverage) {
        if (caseRepository.claimFailedCaseForRequeue(
                caseId, ClassificationFailureReason.INFRASTRUCTURE) == 0) {
            log.debug("Case {} already claimed by another sweep, skipping", caseId);
            return;
        }

        caseRepository.findById(caseId)
                .filter(fresh -> fresh.getStatus() == CaseStatus.CLASSIFICATION_FAILED)
                .ifPresentOrElse(fresh -> doRequeueAfterInfrastructureFailure(fresh, verifiedCoverage),
                        () -> log.debug("Case {} left CLASSIFICATION_FAILED before the sweep reached it", caseId));
    }

    /** Same reset + retrigger as the analyst's manual retry-classification button, actor SYSTEM. */
    private void doRequeueAfterInfrastructureFailure(Case caseRecord, Optional<Coverage> verifiedCoverage) {
        verifiedCoverage.ifPresent(caseRecord::setCoverage);
        caseRecord.setRiskScore(null);
        caseRecord.setRiskBand(null);
        caseRecord.setRulesClassification(null);
        caseRecord.setClassificationAttempts(0);
        caseStatusService.transition(caseRecord, CaseStatus.PENDING_CLASSIFICATION,
                StatusChangeActor.SYSTEM,
                "reencolado automático tras falla de infraestructura");

        claimsAnalysisClient.analyzeAndPersistAsSystem(
                caseRecord, caseDocumentRepository.findByCaseId(caseRecord.getId()));
    }

    private boolean requeueCooldownElapsed(Case caseRecord) {
        return caseStatusService.lastTransitionAt(caseRecord.getId(), CaseStatus.CLASSIFICATION_FAILED,
                        CaseStatus.PENDING_CLASSIFICATION, StatusChangeActor.SYSTEM)
                .map(lastRequeue -> !clock.instant().isBefore(lastRequeue.plus(requeueCooldown)))
                .orElse(true);
    }
}
