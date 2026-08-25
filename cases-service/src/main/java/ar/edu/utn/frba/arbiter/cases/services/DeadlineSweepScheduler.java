package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.DeadlinePriority;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Once a day, flags the cases about to breach their legal response deadline (art. 56 Ley 17.418)
 * and notifies the analyst so a case doesn't lapse unanswered — the alerting half of H0017. The
 * semaphore itself is derived on read ({@link DeadlinePriority} in {@code CaseResponse}); this job
 * is what turns CRITICAL/OVERDUE into an actual notification.
 *
 * <p>Same shape as {@link ClassificationRefreshScheduler}: it runs with no request behind it, so
 * there is no JWT to resolve a tenant from, and the work is cross-tenant — cases live in every
 * insurer's schema. Each run enumerates the active insurers from the common schema and sweeps one
 * schema at a time, setting {@link TenantContext} around each pass.
 *
 * <p>Daily, not every few seconds like the classification sweep: the deadline is a calendar date,
 * so a case can only change priority band once per day.
 */
@Component
@RequiredArgsConstructor
public class DeadlineSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeadlineSweepScheduler.class);

    /** Terminal states: an answered case is never due. Kept in sync with {@code isResponded}. */
    private static final List<String> TERMINAL_STATUSES =
            List.of(CaseStatus.APPROVED.name(), CaseStatus.REJECTED.name());

    /** Only critical or worse notifies; a case is critical at 2 days out, so that's the query window. */
    private static final long NOTIFY_WINDOW_DAYS = 2;

    private final CaseRepository caseRepository;
    private final InsurerRepository insurerRepository;
    private final AnalystNotificationService analystNotificationService;
    private final Clock clock;

    @Scheduled(cron = "${arbiter.deadline-sweep.cron:0 0 8 * * *}")
    public void sweepDeadlines() {
        LocalDate today = LocalDate.now(clock);
        // Read with no tenant set: insurer lives in the common schema, which TenantContext
        // falls back to.
        for (Insurer insurer : insurerRepository.findByActiveTrue()) {
            try {
                TenantContext.set(insurer.getSchemaName());
                sweepCurrentTenant(today);
            } catch (Exception e) {
                // One insurer's failure must not stop the sweep for the rest.
                log.warn("Deadline sweep failed for insurer {} ({}): {}",
                        insurer.getName(), insurer.getSchemaName(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void sweepCurrentTenant(LocalDate today) {
        LocalDate threshold = today.plusDays(NOTIFY_WINDOW_DAYS);
        List<Case> due = caseRepository.findUnansweredDueBy(threshold, TERMINAL_STATUSES);
        if (due.isEmpty()) {
            return;
        }
        log.debug("Deadline sweep: {} case(s) at/under {} in {}", due.size(), threshold, TenantContext.get());
        for (Case caseRecord : due) {
            // responded=false: the query already excluded terminal cases.
            DeadlinePriority priority =
                    DeadlinePriority.of(caseRecord.getResponseDeadline(), today, false);
            // Con NOTIFY_WINDOW_DAYS=2 todo lo que trae la query es CRITICAL u OVERDUE, así que el
            // guard hoy siempre pasa. Queda explícito para que, si algún día se ensancha la ventana
            // (para pre-marcar URGENT/WATCH sin notificar), esos no se notifiquen por error.
            if (priority.notifiable()) {
                analystNotificationService.notifyDeadline(caseRecord, priority, today);
            }
        }
    }
}
