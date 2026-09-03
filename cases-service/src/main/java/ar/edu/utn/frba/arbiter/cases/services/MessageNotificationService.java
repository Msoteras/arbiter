package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.Notification;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.NotificationRepository;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Tells the other side that a message is waiting for them on a case.
 *
 * <p>Only the insured gets an email: the analyst lives in the inbox all day and one mail per
 * message would bury the ones that matter. Neither carries the text — the notice says there is a
 * message and the portal shows it, which keeps whatever the analyst typed inside the application.
 *
 * <p>Best-effort by contract, like its siblings: a delivery failure is logged and never propagates.
 * Losing the notice is bad; losing the message because the notice failed is worse.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageNotificationService {

    private static final String TYPE = "NEW_MESSAGE";
    private static final String EMAIL = "EMAIL";
    /** The analyst notice never leaves the app, so recording it as EMAIL would claim a send. */
    private static final String IN_APP = "IN_APP";
    private static final String SUBJECT = "Tenés un mensaje sobre tu siniestro";
    private static final String TO_INSURED =
            "Un analista te dejó un mensaje sobre tu siniestro. Entrá al portal para leerlo y responder.";
    private static final String TO_ANALYST =
            "El asegurado dejó un mensaje en este expediente.";

    private final NotificationRepository notificationRepository;
    private final CaseNotificationService caseNotificationService;
    private final SendGridAdapter sendGridAdapter;

    /**
     * @param senderRole who wrote the message; the recipient is the other side
     */
    public void notifyNewMessage(Case caseRecord, StatusChangeActor senderRole) {
        try {
            if (senderRole == StatusChangeActor.ANALYST) {
                notifyInsured(caseRecord);
            } else {
                notifyAnalyst(caseRecord);
            }
        } catch (Exception | LinkageError e) {
            // LinkageError too: a missing mail SDK surfaces as NoClassDefFoundError, not an Exception.
            log.error("Could not notify the new message on case {}", caseRecord.getId(), e);
        }
    }

    private void notifyInsured(Case caseRecord) {
        Insured insured = caseRecord.getInsured();
        if (insured == null || insured.getUser() == null) {
            log.warn("Case {} has no insured account to notify", caseRecord.getId());
            return;
        }
        Notification notification = save(caseRecord, insured.getUser().getId(), TO_INSURED, EMAIL);
        caseNotificationService.recipientEmail(insured).ifPresentOrElse(
                address -> send(notification, address),
                () -> log.warn("No email for the insured of case {}, notification {} not sent",
                        caseRecord.getId(), notification.getId()));
    }

    /**
     * In-app only, and only when the case has an owner: {@code recipient_id} is NOT NULL, so an
     * unassigned case has nobody to point the row at. The message still shows on the case itself,
     * which is where whoever takes it will look.
     */
    private void notifyAnalyst(Case caseRecord) {
        ClaimsAnalyst analyst = caseRecord.getAnalyst();
        if (analyst == null || analyst.getUser() == null) {
            return;
        }
        save(caseRecord, analyst.getUser().getId(), TO_ANALYST, IN_APP);
    }

    private Notification save(Case caseRecord, Long recipientId, String content, String channel) {
        return notificationRepository.save(Notification.builder()
                .recipientId(recipientId)
                .caseEntity(caseRecord)
                .type(TYPE)
                .channel(channel)
                .content(content)
                .createdAt(Instant.now())
                .sent(false)
                .read(false)
                .build());
    }

    private void send(Notification notification, String address) {
        try {
            // sent=true only if the mail really went out: with no API key the adapter no-ops.
            if (!sendGridAdapter.send(address, SUBJECT, body(notification))) {
                return;
            }
            notification.setSent(true);
            notification.setSentAt(Instant.now());
            notificationRepository.save(notification);
        } catch (Exception e) {
            log.error("Could not email notification {} to {}", notification.getId(), address, e);
        }
    }

    private String body(Notification notification) {
        return """
                <p>Hola,</p>
                <p>%s</p>
                <p>Siniestro <strong>#%d</strong>.</p>
                <p>Arbiter</p>
                """.formatted(TO_INSURED, notification.getCaseEntity().getId());
    }
}
