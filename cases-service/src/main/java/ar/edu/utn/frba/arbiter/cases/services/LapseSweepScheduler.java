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
 * Once a day, lapses cases still in {@link CaseStatus#AWAITING_DOCUMENTATION} 18 months after
 * filing — an internal insurer rule for insured inaction, separate from the legal prescription
 * period. Nothing else ever moves a case out of that status.
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

    /**
     * Uses {@code transitionIfStillIn}: the list was loaded at the start of the sweep and another
     * instance may be sweeping the same schema, so a plain transition could lapse a case twice and
     * email the insured twice. Losing the race is not an error — the case already got there.
     */
    private void sweepCurrentTenant(Instant threshold) {
        List<Case> stale = caseRepository.findStaleByStatus(CaseStatus.AWAITING_DOCUMENTATION.name(), threshold);
        if (stale.isEmpty()) {
            return;
        }
        log.info("Lapse sweep: closing {} case(s) idle since before {} in {}",
                stale.size(), threshold, TenantContext.get());
        for (Case caseRecord : stale) {
            caseStatusService.transitionIfStillIn(caseRecord, CaseStatus.AWAITING_DOCUMENTATION,
                    CaseStatus.LAPSED, StatusChangeActor.SYSTEM,
                    "Caducidad por " + INACTION_MONTHS
                            + " meses de inacción del asegurado desde la denuncia (regla interna)");
        }
    }
}
