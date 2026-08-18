package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sweeps cases still waiting on their classification and asks classification-service whether
 * the result is ready.
 *
 * <p>Unlike everything else in this module, this runs with no request behind it, so there is no
 * JWT to resolve a tenant from — and the work is inherently cross-tenant: pending cases live in
 * every insurer's schema. Each tick therefore enumerates the active insurers from the common
 * schema and sweeps one schema at a time, setting {@link TenantContext} around each pass.
 */
@Component
@RequiredArgsConstructor
public class ClassificationRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClassificationRefreshScheduler.class);

    private final CaseRepository caseRepository;
    private final CaseStatusService caseStatusService;
    private final ClaimsAnalysisClient claimsAnalysisClient;
    private final InsurerRepository insurerRepository;

    @Value("${arbiter.classification-refresh.max-attempts:120}")
    private int maxAttempts;

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
     */
    private void incrementAttempts(Case caseRecord) {
        int attempts = caseRecord.getClassificationAttempts() + 1;
        if (attempts >= maxAttempts) {
            log.error("Case {} marked as CLASSIFICATION_FAILED after {} attempts", caseRecord.getId(), attempts);
            caseRecord.setClassificationAttempts(attempts);
            caseStatusService.transition(caseRecord, CaseStatus.CLASSIFICATION_FAILED,
                    StatusChangeActor.SYSTEM, "clasificación fallida tras " + attempts + " reintentos");
        } else {
            caseRepository.updateClassificationAttempts(caseRecord.getId(), attempts);
        }
    }
}
