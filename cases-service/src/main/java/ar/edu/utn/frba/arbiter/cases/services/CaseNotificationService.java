package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.dto.NotificationResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.NotificationNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.Notification;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.NotificationRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Tells the insured their case moved, by email and in the in-app panel.
 *
 * <p>Only statuses that ask them for something or are the outcome. The rest is internal traffic,
 * and telling them would leak what the story forbids: no classification, no score, no reasons.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaseNotificationService {

    private static final String CHANNEL = "EMAIL";

    /**
     * Separate from the frontend's status labels, which collapse APPROVED and REJECTED into a
     * single "Terminado" — precisely the distinction that matters in an email. Says "siniestro"
     * because that's what the portal calls it; the expediente is the analyst's side of it.
     */
    private static final Map<CaseStatus, Message> MESSAGES = Map.of(
            CaseStatus.PENDING_CLASSIFICATION, new Message(
                    "Recibimos tu denuncia",
                    "Recibimos tu denuncia y ya estamos trabajando en tu siniestro. "
                            + "Te vamos a avisar por este medio cuando haya novedades."),
            CaseStatus.AWAITING_DOCUMENTATION, new Message(
                    "Necesitamos documentación de tu siniestro",
                    "Para poder seguir con tu siniestro necesitamos que subas la documentación "
                            + "que falta. Entrá al portal para ver cuál es y cargarla."),
            CaseStatus.APPROVED, new Message(
                    "Tu siniestro fue aprobado",
                    "Revisamos tu siniestro y fue aprobado. Vas a recibir la información sobre "
                            + "los pasos siguientes."),
            CaseStatus.REJECTED, new Message(
                    "Novedades sobre tu siniestro",
                    "Revisamos tu siniestro y no fue aprobado. Si querés conocer los motivos o no "
                            + "estás de acuerdo, podés comunicarte con nosotros."));

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final InsurerRepository insurerRepository;
    private final SendGridAdapter sendGridAdapter;

    /** Best-effort by contract: a delivery failure must never break the case transition. */
    public void notifyStatusChange(Case caseRecord, CaseStatus newStatus) {
        try {
            Message message = MESSAGES.get(newStatus);
            if (message == null) {
                return;
            }
            deliver(caseRecord, newStatus, message);
        } catch (Exception | LinkageError e) {
            // LinkageError too: a missing mail SDK surfaces as NoClassDefFoundError, which isn't an
            // Exception, and cost us a 500 on an approval that had already been applied.
            log.error("Could not notify case {} moving to {}", caseRecord.getId(), newStatus, e);
        }
    }

    private void deliver(Case caseRecord, CaseStatus newStatus, Message message) {
        Insured insured = caseRecord.getInsured();
        if (insured == null || insured.getUser() == null) {
            // recipient_id is NOT NULL, so an insured who never signed up can't have a row.
            log.warn("Case {} has no insured account to notify", caseRecord.getId());
            return;
        }

        // Only the id off the association: any other field would initialize the lazy proxy, and
        // with open-in-view off there's no session here to do it.
        Notification notification = notificationRepository.save(Notification.builder()
                .recipientId(insured.getUser().getId())
                .caseEntity(caseRecord)
                .type(newStatus.name())
                .channel(CHANNEL)
                .content(message.body())
                .createdAt(Instant.now())
                .sent(false)
                .read(false)
                .build());

        recipientEmail(insured).ifPresentOrElse(
                address -> send(notification, address, message, caseRecord),
                () -> log.warn("No email for the insured of case {}, notification {} not sent",
                        caseRecord.getId(), notification.getId()));
    }

    private void send(Notification notification, String address, Message message, Case caseRecord) {
        try {
            sendGridAdapter.send(address, message.subject(), body(message, caseRecord));
            notification.setSent(true);
            notification.setSentAt(Instant.now());
            notificationRepository.save(notification);
        } catch (Exception e) {
            // The row stays sent=false: the panel still shows it and a retry can find what never went out.
            log.error("Could not email notification {} to {}", notification.getId(), address, e);
        }
    }

    /**
     * The insurer's contact address, falling back to the account's. {@code insured} is a snapshot
     * of the insurer's DB so its email can be stale or absent; the account's always exists.
     */
    private Optional<String> recipientEmail(Insured insured) {
        if (insured.getEmail() != null && !insured.getEmail().isBlank()) {
            return Optional.of(insured.getEmail());
        }
        // Re-read rather than insured.getUser().getEmail(): lazy proxy, no session here.
        return userRepository.findById(insured.getUser().getId())
                .map(User::getEmail)
                .filter(email -> email != null && !email.isBlank());
    }

    public List<NotificationResponse> forCurrentUser() {
        Optional<Long> userId = currentUserId();
        if (userId.isEmpty()) {
            return List.of();
        }
        List<NotificationResponse> merged = acrossOwnInsurers(slug ->
                notificationRepository.findByRecipientIdOrderByCreatedAtDesc(userId.get()).stream()
                        .map(notification -> NotificationResponse.from(notification, slug))
                        .toList());
        // Re-sorted after merging: each schema comes back ordered on its own, and interleaving two
        // insurers by date is the whole point of showing them in one panel.
        return merged.stream()
                .sorted(Comparator.comparing(NotificationResponse::createdAt).reversed())
                .toList();
    }

    public long unreadCountForCurrentUser() {
        return currentUserId()
                .map(userId -> acrossOwnInsurers(slug ->
                        List.of(notificationRepository.countByRecipientIdAndReadFalse(userId))))
                .orElseGet(List::of)
                .stream()
                .mapToLong(Long::longValue)
                .sum();
    }

    /**
     * An insured can be a client of more than one insurer, and their notifications live in each
     * insurer's schema — like their cases, which the portal already merges. Reading only the active
     * tenant would show them siniestros from both companies under a bell that counts one.
     *
     * <p>For an analyst or a referente the list comes back empty and this runs once on the active
     * tenant: they belong to a single insurer, and reaching into another would be a tenant leak.
     * The schemas come from {@code insurerIds}, a <b>signed</b> claim, never from request input.
     */
    private <T> List<T> acrossOwnInsurers(Function<String, List<T>> perTenant) {
        List<Insurer> insurers = ownInsurers();
        if (insurers.isEmpty()) {
            return perTenant.apply(null);
        }
        String callerTenant = TenantContext.get();
        List<T> merged = new ArrayList<>();
        try {
            for (Insurer insurer : insurers) {
                TenantContext.set(insurer.getSchemaName());
                merged.addAll(perTenant.apply(InsurerSlug.of(insurer)));
            }
        } finally {
            // Sin esto la conexión vuelve al pool viendo el esquema equivocado y se lo lleva puesto
            // el próximo request. Mismo cuidado que en InsuredCaseAggregator.
            TenantContext.set(callerTenant);
        }
        return merged;
    }

    private List<Insurer> ownInsurers() {
        CallerContext.Caller caller = CallerContext.get();
        if (caller.insuredId() == null || caller.insurerIds().isEmpty()) {
            return List.of();
        }
        return insurerRepository.findAllById(caller.insurerIds()).stream()
                .filter(Insurer::isActive)
                .toList();
    }

    /**
     * Idempotent: the timestamp keeps the first time it was seen.
     *
     * <p>{@code insurerSlug} disambiguates the id, which repeats across schemas. It's matched
     * against the caller's own insurers, so naming another company's is a 404 and not a read of it.
     */
    public void markRead(Long notificationId, String insurerSlug) {
        Long userId = currentUserId().orElseThrow(NotificationNotFoundException::new);
        if (insurerSlug == null || insurerSlug.isBlank()) {
            markRead(notificationId, userId);
            return;
        }
        Insurer insurer = ownInsurers().stream()
                .filter(candidate -> InsurerSlug.matches(candidate, insurerSlug))
                .findFirst()
                .orElseThrow(NotificationNotFoundException::new);
        String callerTenant = TenantContext.get();
        try {
            TenantContext.set(insurer.getSchemaName());
            markRead(notificationId, userId);
        } finally {
            TenantContext.set(callerTenant);
        }
    }

    private void markRead(Long notificationId, Long userId) {
        Notification notification = notificationRepository.findByIdAndRecipientId(notificationId, userId)
                .orElseThrow(NotificationNotFoundException::new);
        if (notification.isRead()) {
            return;
        }
        notification.setRead(true);
        notification.setReadAt(Instant.now());
        notificationRepository.save(notification);
    }

    /**
     * Opening the panel means the whole list was seen — one call beats one per notification. Clears
     * every insurer's, because the panel showed every insurer's.
     */
    public void markAllRead() {
        currentUserId().ifPresent(userId -> acrossOwnInsurers(slug -> {
            Instant now = Instant.now();
            List<Notification> unread = notificationRepository.findByRecipientIdAndReadFalse(userId);
            unread.forEach(notification -> {
                notification.setRead(true);
                notification.setReadAt(now);
            });
            notificationRepository.saveAll(unread);
            return List.of();
        }));
    }

    private Optional<Long> currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Optional.empty();
        }
        return userRepository.findByEmail(authentication.getName()).map(User::getId);
    }

    private String body(Message message, Case caseRecord) {
        return """
                <p>Hola,</p>
                <p>%s</p>
                <p>Siniestro <strong>#%d</strong>.</p>
                <p>Arbiter</p>
                """.formatted(message.body(), caseRecord.getId());
    }

    private record Message(String subject, String body) {
    }
}
