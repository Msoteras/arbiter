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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Once a day, closes cases that fell asleep waiting on the insured: 18 months from the denuncia
 * with no documentation ever brought (still in {@link CaseStatus#AWAITING_DOCUMENTATION}).
 *
 * <p>Doc de dominio BBVA (§9): "inacción del asegurado ante requerimientos" is a regla interna,
 * separate from the legal prescripción (1 año desde el hecho — see the deterministic gate in
 * classification-service) and from the art. 56 response term (which this exact status also
 * interrupts — see {@code CaseStatusService.PAUSING_STATUSES}). A case can sit in
 * {@code AWAITING_DOCUMENTATION} forever otherwise: nothing else ever moves it out.
 *
 * <p>Same shape as {@link DeadlineSweepScheduler}: no request behind it, so no JWT to resolve a
 * tenant from, and the work is cross-tenant.
 */
@Component
@RequiredArgsConstructor
public class LapseSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(LapseSweepScheduler.class);

    private static final long INACTION_MONTHS = 18;

    private final CaseRepository caseRepository;
    private final InsurerRepository insurerRepository;
    private final CaseStatusService caseStatusService;
    private final Clock clock;

    @Scheduled(cron = "${arbiter.lapse-sweep.cron:0 30 8 * * *}")
    public void sweepLapsedCases() {
        Instant threshold = LocalDateTime.now(clock).minusMonths(INACTION_MONTHS).toInstant(ZoneOffset.UTC);
        for (Insurer insurer : insurerRepository.findByActiveTrue()) {
            try {
                TenantContext.set(insurer.getSchemaName());
                sweepCurrentTenant(threshold);
            } catch (Exception e) {
                // One insurer's failure must not stop the sweep for the rest.
                log.warn("Lapse sweep failed for insurer {} ({}): {}",
                        insurer.getName(), insurer.getSchemaName(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void sweepCurrentTenant(Instant threshold) {
        List<Case> stale = caseRepository.findStaleByStatus(CaseStatus.AWAITING_DOCUMENTATION.name(), threshold);
        if (stale.isEmpty()) {
            return;
        }
        log.info("Lapse sweep: closing {} case(s) idle since before {} in {}",
                stale.size(), threshold, TenantContext.get());
        for (Case caseRecord : stale) {
            caseStatusService.transition(caseRecord, CaseStatus.LAPSED, StatusChangeActor.SYSTEM,
                    "Caducidad por " + INACTION_MONTHS
                            + " meses de inacción del asegurado desde la denuncia (regla interna)");
        }
    }
}
