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
 * Two background sweeps over cases waiting on classification, both cross-tenant (see below) and
 * both with no request behind them.
 *
 * <ul>
 *   <li>{@link #refreshPendingCases()}: asks classification-service whether a
 *       {@code PENDING_CLASSIFICATION} case's result is ready yet, and gives up to
 *       {@code CLASSIFICATION_FAILED} after {@code max-attempts}.</li>
 *   <li>{@link #recoverInfrastructureFailures()}: separately, and on its own slower cadence,
 *       requeues a {@code CLASSIFICATION_FAILED} case back to classification whenever the reason
 *       recorded for it was {@link ClassificationFailureReason#INFRASTRUCTURE} — a dependency that
 *       was down, not the claim itself being unclassifiable. Without this, nothing ever asks
 *       classification-service again for that case: this class's other sweep only polls for a
 *       result, it never re-triggers the run, so once a case reaches {@code CLASSIFICATION_FAILED}
 *       it stayed there until an analyst noticed and pressed the manual retry button — even after
 *       the outage that caused it was long over.</li>
 * </ul>
 *
 * <p>Both live in the same class because both are the same kind of background job — no request, no
 * JWT to resolve a tenant from, inherently cross-tenant — and sharing the class means one place
 * enumerates the active insurers and drives {@link TenantContext} instead of two. They keep
 * separate {@code @Scheduled} triggers, though: {@link #recoverInfrastructureFailures()} runs far
 * less often on purpose — see its javadoc.
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

    /** La ventana efectiva, para no tener que deducirla de cuándo se rindió el barrido. */
    @PostConstruct
    void logWindow() {
        log.info("[Refresh] Ventana: interval-ms={} x max-attempts={} = {} min",
                intervalMs, maxAttempts, (intervalMs * maxAttempts) / 60000);
    }

    @Scheduled(fixedDelayString = "${arbiter.classification-refresh.interval-ms:5000}")
    public void refreshPendingCases() {
        // Read with no tenant set: insurer lives in the common schema, which TenantContext
        // falls back to.
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
     * Sube el contador y, al agotarse, marca el expediente como fallido.
     *
     * <p>El contador se escribe con un update puntual y no con {@code save(caseRecord)}: la
     * entidad se cargó al principio del barrido, y guardarla entera reescribía toda la fila desde
     * esa copia vieja, revirtiendo en silencio cualquier cambio hecho en el medio — el caso
     * concreto que lo destapó fue un expediente que volvía solo de {@code CLASSIFICATION_FAILED}
     * a {@code PENDING_CLASSIFICATION} cada pocos segundos, deshaciendo el reintento del analista.
     * El barrido es dueño del contador y de nada más.
     *
     * <p>Y ese update es <b>condicional</b>, porque el barrido no corre solo: la base de Railway es
     * compartida por el equipo, así que cada stack local levantado suma otro scheduler barriendo
     * los mismos expedientes. Avanzar el contador es lo que hace de turno — el que lo consigue
     * sigue, el que llega tarde se retira. Sin eso, dos barridos leían el mismo valor, los dos lo
     * daban por agotado y los dos marcaban el expediente como fallido, dejando en
     * {@code case_status_history} dos filas idénticas con el mismo número de reintentos.
     */
    private void incrementAttempts(Case caseRecord) {
        int previous = caseRecord.getClassificationAttempts();
        int attempts = previous + 1;

        if (caseRepository.advanceClassificationAttempts(caseRecord.getId(), previous, attempts) == 0) {
            // Otro barrido ya avanzó este expediente en esta vuelta: es su turno, no el nuestro.
            log.debug("Case {} already advanced by another sweep, skipping", caseRecord.getId());
            return;
        }

        if (attempts < maxAttempts) {
            return;
        }

        // Releído de la base y no reusando `caseRecord`: la copia del barrido es de hace varios
        // segundos y transition() valida la transición contra el estado que traiga la entidad. Con
        // la copia vieja, un expediente que ya salió de PENDING_CLASSIFICATION (lo reintentó un
        // analista, u otro barrido lo resolvió) pasaría igual la validación y se le escribiría una
        // transición que no corresponde.
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
     * classification-service already wrote {@code classificationFailureReason} onto this same row
     * (see {@code CaseOutcomeRepository.recordClassificationFailure}) by the time the sweep gives
     * up — this just surfaces it in the transition's reason instead of leaving the generic
     * "N reintentos" string as the only trace of why. Empty when nothing was recorded (e.g. the
     * poll itself kept failing rather than the classification run).
     */
    private String failureSuffix(Case caseRecord) {
        ClassificationFailureReason reason = caseRecord.getClassificationFailureReason();
        return reason == null ? "" : " (" + reason.name().toLowerCase() + ")";
    }

    /**
     * Requeues every {@code CLASSIFICATION_FAILED} case whose last run gave up for an
     * {@link ClassificationFailureReason#INFRASTRUCTURE} reason.
     *
     * <p>A far wider {@code fixedDelayString} than {@link #refreshPendingCases()} on purpose, not
     * just a smaller number: {@code ClaimClassificationService.processClaimClassification}'s own
     * {@code @Retryable} already spends several minutes retrying before a case even reaches
     * {@code CLASSIFICATION_FAILED} (see its javadoc), so requeuing it again seconds later would
     * just hammer a dependency that's still down and start another multi-minute retry window on
     * top of the one that just finished. Spacing this sweep out lets that window do its job first;
     * defaults to 5 min, {@code arbiter.classification-refresh.recovery-interval-ms} to tune it.
     */
    @Scheduled(fixedDelayString = "${arbiter.classification-refresh.recovery-interval-ms:300000}")
    public void recoverInfrastructureFailures() {
        for (Insurer insurer : insurerRepository.findByActiveTrue()) {
            try {
                TenantContext.set(insurer.getSchemaName());
                recoverInfrastructureFailuresForCurrentTenant();
            } catch (Exception e) {
                // One insurer's failure must not stop the sweep for the rest, same as the poller.
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
     * Toma el turno sobre el expediente y, si se lo queda, lo reencola.
     *
     * <p>El turno se toma con un compare-and-set
     * ({@link CaseRepository#claimFailedCaseForRequeue}) y no releyendo la entidad y filtrando en
     * Java, por lo mismo que {@link #incrementAttempts} usa el suyo: este barrido no corre solo. Un
     * simple "releer y chequear" deja la ventana abierta igual — dos barridos leen el mismo
     * {@code CLASSIFICATION_FAILED}, los dos pasan el filtro y los dos reencolan. El CAS es lo que
     * cierra eso: sólo uno consigue limpiar el motivo.
     *
     * <p>Y encima se relee después del CAS, porque el CAS mira el motivo pero no el estado: un
     * expediente que salió de {@code CLASSIFICATION_FAILED} en el medio (lo reintentó un analista)
     * no tiene que volver a pasar por acá aunque el motivo siguiera puesto.
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
        // transition() re-validates CLASSIFICATION_FAILED → PENDING_CLASSIFICATION against the
        // state actually in the DB — a case an analyst already retried by hand in between simply
        // won't be CLASSIFICATION_FAILED any more and is skipped by the query above regardless.
        caseStatusService.transition(caseRecord, CaseStatus.PENDING_CLASSIFICATION,
                StatusChangeActor.SYSTEM,
                "reencolado automático tras falla de infraestructura");

        claimsAnalysisClient.analyzeAndPersistAsSystem(
                caseRecord, caseDocumentRepository.findByCaseId(caseRecord.getId()));
    }
}
