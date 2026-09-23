package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationFailureReason;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Two cross-tenant background sweeps over cases waiting on classification:
 * <ul>
 *   <li>{@link #refreshPendingCases()} polls classification-service for {@code PENDING_CLASSIFICATION}
 *       results and gives up to {@code CLASSIFICATION_FAILED} after {@code max-attempts}.</li>
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
    private final InsurerRepository insurerRepository;

    @Value("${arbiter.classification-refresh.max-attempts:120}")
    private int maxAttempts;

    @Value("${arbiter.classification-refresh.interval-ms:5000}")
    private long intervalMs;

    @PostConstruct
    void logWindow() {
        log.info("[Refresh] Ventana: interval-ms={} x max-attempts={} = {} min",
                intervalMs, maxAttempts, (intervalMs * maxAttempts) / 60000);
    }

    @Scheduled(fixedDelayString = "${arbiter.classification-refresh.interval-ms:5000}")
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

        if (attempts < maxAttempts) {
            return;
        }

        // Re-read rather than reusing the sweep's copy: transition() validates against the state
        // the entity carries, and the case may have left PENDING_CLASSIFICATION in the meantime.
        caseRepository.findById(caseRecord.getId())
                .filter(fresh -> fresh.getStatus() == CaseStatus.PENDING_CLASSIFICATION)
                .ifPresentOrElse(fresh -> {
                    log.error("Case {} marked as CLASSIFICATION_FAILED after {} attempts",
                            fresh.getId(), attempts);
                    caseStatusService.transition(fresh, CaseStatus.CLASSIFICATION_FAILED,
                            StatusChangeActor.SYSTEM,
                            "clasificación fallida tras " + attempts + " reintentos" + failureSuffix(fresh));
                }, () -> log.debug("Case {} left PENDING_CLASSIFICATION before the sweep gave up",
                        caseRecord.getId()));
    }

    /**
     * Surfaces the reason classification-service recorded on the row. Empty when none was recorded,
     * e.g. when the poll itself kept failing.
     */
    private String failureSuffix(Case caseRecord) {
        ClassificationFailureReason reason = caseRecord.getClassificationFailureReason();
        return reason == null ? "" : " (" + reason.name().toLowerCase() + ")";
    }

    /**
     * Runs far less often than the poller on purpose: classification-service already retries for
     * several minutes before a case fails, and requeuing seconds later would just hammer a
     * dependency that's still down.
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
        List<Case> failed = caseRepository.findFailedByReason(ClassificationFailureReason.INFRASTRUCTURE);
        if (failed.isEmpty()) {
            return;
        }
        log.info("Requeuing {} CLASSIFICATION_FAILED case(s) in {} after an infrastructure failure",
                failed.size(), TenantContext.get());
        for (Case caseRecord : failed) {
            try {
                requeueAfterInfrastructureFailure(caseRecord.getId());
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
    private void requeueAfterInfrastructureFailure(Long caseId) {
        if (caseRepository.claimFailedCaseForRequeue(
                caseId, ClassificationFailureReason.INFRASTRUCTURE) == 0) {
            log.debug("Case {} already claimed by another sweep, skipping", caseId);
            return;
        }

        caseRepository.findById(caseId)
                .filter(fresh -> fresh.getStatus() == CaseStatus.CLASSIFICATION_FAILED)
                .ifPresentOrElse(this::doRequeueAfterInfrastructureFailure,
                        () -> log.debug("Case {} left CLASSIFICATION_FAILED before the sweep reached it", caseId));
    }

    /** Same reset + retrigger as the analyst's manual retry-classification button, actor SYSTEM. */
    private void doRequeueAfterInfrastructureFailure(Case caseRecord) {
        caseRecord.setRiskScore(null);
        caseRecord.setRiskBand(null);
        caseRecord.setDeterministicFastTrack(false);
        caseRecord.setClassificationAttempts(0);
        caseStatusService.transition(caseRecord, CaseStatus.PENDING_CLASSIFICATION,
                StatusChangeActor.SYSTEM,
                "reencolado automático tras falla de infraestructura");

        claimsAnalysisClient.analyzeAndPersistAsSystem(
                caseRecord, caseDocumentRepository.findByCaseId(caseRecord.getId()));
    }
}
