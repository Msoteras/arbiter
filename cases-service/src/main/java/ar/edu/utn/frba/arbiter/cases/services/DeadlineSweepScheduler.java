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
 * Once a day, notifies the analyst about cases about to breach their art. 56 response deadline.
 * The priority itself is derived on read ({@link DeadlinePriority}); this job only turns
 * CRITICAL/OVERDUE into a notification.
 *
 * <p>No request behind it, so there is no JWT to resolve a tenant from: it sweeps each active
 * insurer's schema in turn. Daily is enough because the deadline is a calendar date.
 */
@Component
@RequiredArgsConstructor
public class DeadlineSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeadlineSweepScheduler.class);

    /**
     * Never due: terminal cases are closed, and in a pausing status the art. 56 term is interrupted,
     * so {@code responseDeadline} is a frozen date. Must match {@code CaseStatusService.isDeadlineRunning}.
     */
    private static final List<String> DEADLINE_INACTIVE_STATUSES = List.of(
            CaseStatus.APPROVED.name(), CaseStatus.REJECTED.name(), CaseStatus.LAPSED.name(),
            CaseStatus.AWAITING_DOCUMENTATION.name(), CaseStatus.PENDING_EXPERT_REPORT.name());

    /** Only critical or worse notifies; a case is critical at 2 days out, so that's the query window. */
    private static final long NOTIFY_WINDOW_DAYS = 2;

    private final CaseRepository caseRepository;
    private final InsurerRepository insurerRepository;
    private final AnalystNotificationService analystNotificationService;
    private final Clock clock;

    @Scheduled(cron = "${arbiter.deadline-sweep.cron:0 0 8 * * *}")
    public void sweepDeadlines() {
        LocalDate today = LocalDate.now(clock);
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
        List<Case> due = caseRepository.findUnansweredDueBy(threshold, DEADLINE_INACTIVE_STATUSES);
        if (due.isEmpty()) {
            return;
        }
        log.debug("Deadline sweep: {} case(s) at/under {} in {}", due.size(), threshold, TenantContext.get());
        for (Case caseRecord : due) {
            // responded=false: the query already excluded terminal and paused cases.
            DeadlinePriority priority =
                    DeadlinePriority.of(caseRecord.getResponseDeadline(), today, false);
            // Always true with the current window; guards against notifying URGENT/WATCH if it widens.
            if (priority.notifiable()) {
                analystNotificationService.notifyDeadline(caseRecord, priority, today);
            }
        }
    }
}
