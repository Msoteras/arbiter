package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.Notification;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.NotificationRepository;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import ar.edu.utn.frba.arbiter.common.enums.DeadlinePriority;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Tells analysts a case is running out of time to be answered. Best-effort: a delivery failure is
 * logged and never propagates, so the daily sweep always finishes the schema.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalystNotificationService {

    private static final String CHANNEL = "EMAIL";

    private final NotificationRepository notificationRepository;
    private final ClaimsAnalystRepository claimsAnalystRepository;
    private final SendGridAdapter sendGridAdapter;

    /**
     * Idempotent per (case, recipient, level): a case escalating from CRITICAL to OVERDUE notifies
     * once more because the level changed.
     *
     * <p>Deliberately not {@code @Transactional}: holding a DB connection while waiting on SendGrid
     * (once per analyst for an unowned case) would exhaust the pool under load.
     *
     * @param today the sweep's reference day, used only to word the message
     */
    public void notifyDeadline(Case caseRecord, DeadlinePriority priority, LocalDate today) {
        if (!priority.notifiable()) {
            return;
        }
        List<ClaimsAnalyst> recipients = resolveRecipients(caseRecord);
        if (recipients.isEmpty()) {
            log.warn("Case {} is {} but the insurer has no analyst to notify",
                    caseRecord.getId(), priority);
            return;
        }
        String type = "DEADLINE_" + priority.name();
        Message message = messageFor(caseRecord, priority, today);
        for (ClaimsAnalyst analyst : recipients) {
            try {
                notifyOne(caseRecord, analyst, type, message);
            } catch (Exception | LinkageError e) {
                // Missing mail SDK surfaces as a LinkageError, not an Exception; one analyst's
                // failure must not stop the rest, nor the sweep.
                log.error("Could not notify analyst {} about case {} ({})",
                        analyst.getId(), caseRecord.getId(), type, e);
            }
        }
    }

    private void notifyOne(Case caseRecord, ClaimsAnalyst analyst, String type, Message message) {
        // Only the id off the association: any other field would initialize the lazy proxy, and
        // with open-in-view off there's no session here to do it.
        Long recipientId = analyst.getUser().getId();
        if (notificationRepository.existsByCaseEntityIdAndRecipientIdAndType(
                caseRecord.getId(), recipientId, type)) {
            return;
        }
        Notification notification = notificationRepository.save(Notification.builder()
                .recipientId(recipientId)
                .caseEntity(caseRecord)
                .type(type)
                .channel(CHANNEL)
                .content(message.body())
                .createdAt(Instant.now())
                .sent(false)
                .read(false)
                .build());

        String email = analyst.getEmail();
        if (email == null || email.isBlank()) {
            log.warn("Analyst {} has no email; notification {} not sent", analyst.getId(),
                    notification.getId());
            return;
        }
        try {
            sendGridAdapter.send(email, message.subject(), message.htmlBody());
            notification.setSent(true);
            notification.setSentAt(Instant.now());
            notificationRepository.save(notification);
        } catch (Exception e) {
            // The row stays sent=false: the panel still shows it and a retry can find what never went out.
            log.error("Could not email deadline notification {} to {}", notification.getId(), email, e);
        }
    }

    /** Assigned analyst if any; otherwise the whole team, so a critical unowned case reaches someone. */
    private List<ClaimsAnalyst> resolveRecipients(Case caseRecord) {
        ClaimsAnalyst assigned = caseRecord.getAnalyst();
        return assigned != null ? List.of(assigned) : claimsAnalystRepository.findAll();
    }

    private Message messageFor(Case caseRecord, DeadlinePriority priority, LocalDate today) {
        long id = caseRecord.getId();
        LocalDate deadline = caseRecord.getResponseDeadline();
        String insured = caseRecord.getInsured().fullName();
        String cause = caseRecord.getClaimCause().getName();
        if (priority == DeadlinePriority.OVERDUE) {
            long overdueDays = ChronoUnit.DAYS.between(deadline, today);
            String subject = "Expediente #" + id + " VENCIDO — plazo de respuesta incumplido";
            String body = "El expediente #" + id + " (" + cause + " · " + insured
                    + ") venció el " + deadline + " y todavía no fue respondido ("
                    + overdueDays + " día(s) de atraso). Requiere resolución inmediata.";
            return new Message(subject, body, id);
        }
        long daysLeft = ChronoUnit.DAYS.between(today, deadline);
        String subject = "Expediente #" + id + " crítico — vence en " + daysLeft + " día(s)";
        String body = "El expediente #" + id + " (" + cause + " · " + insured
                + ") vence el " + deadline + " y todavía no fue respondido. Quedan "
                + daysLeft + " día(s) para expedirse.";
        return new Message(subject, body, id);
    }

    /**
     * {@code body} is plain text because the in-app panel renders {@code notification.content} as is;
     * {@code htmlBody} is derived from it for the email so the two can't drift apart.
     */
    private record Message(String subject, String body, long caseId) {

        String htmlBody() {
            return body.replace("#" + caseId, "<b>#" + caseId + "</b>");
        }
    }
}
