package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.dto.CaseMessageEvent;
import ar.edu.utn.frba.arbiter.cases.dto.CaseMessageResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseMessageThreadResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.ClosedConversationException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseMessage;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseMessageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseStatusHistoryRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The conversation between an analyst and an insured about one case.
 *
 * <p>Deliberately outside the case's lifecycle: a message never moves the expediente. Asking for a
 * clarification is not the same as {@code AWAITING_DOCUMENTATION}, which the classification owns
 * and an upload closes — giving that state a second door would make "waiting on the insured" mean
 * two different things.
 *
 * <p>Two analysts are one side. The insured is talking to the insurer's claims desk, and a case
 * gets reassigned; a reply written by whoever holds the case now continues the same thread.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaseMessageService {

    private final CaseMessageRepository messageRepository;
    private final CaseRepository caseRepository;
    private final CaseStatusHistoryRepository statusHistoryRepository;
    private final UserRepository userRepository;
    private final CaseAccessPolicy accessPolicy;
    private final InsurerTenantScope tenantScope;
    private final MessageNotificationService notificationService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    /**
     * How long after the case is resolved the thread still takes messages. The rejection email
     * tells the insured they can get in touch if they disagree, so closing the channel the moment
     * the case closes would contradict the only message that invites them to write.
     */
    @Value("${arbiter.messaging.reply-window-days:7}")
    private int replyWindowDays;

    /** Reading does not mark anything read — see {@link #markRead(Long, String)}. */
    public CaseMessageThreadResponse thread(Long caseId, String insurerSlug) {
        return tenantScope.forCase(caseId, insurerSlug, () -> {
            Case caseRecord = readableCase(caseId);
            StatusChangeActor party = accessPolicy.currentParty();
            List<CaseMessage> messages = messageRepository.findByCaseIdOrderByCreatedAtAsc(caseId);

            boolean open = acceptsMessages(caseRecord);
            return new CaseMessageThreadResponse(
                    messages.stream().map(m -> CaseMessageResponse.from(m, m.getSenderRole() == party)).toList(),
                    (int) messages.stream().filter(m -> isIncomingUnread(m, party)).count(),
                    open && isParty(party),
                    open ? null : new ClosedConversationException(replyWindowDays).getMessage(),
                    CaseTopic.of(TenantContext.get(), caseId),
                    isParty(party) ? party.name() : null);
        });
    }

    public CaseMessageResponse post(Long caseId, String insurerSlug, String body) {
        return tenantScope.forCase(caseId, insurerSlug, () -> {
            Case caseRecord = readableCase(caseId);
            StatusChangeActor party = accessPolicy.currentParty();
            if (!isParty(party)) {
                throw new AccessDeniedException("Sólo el asegurado y el analista escriben en el expediente.");
            }
            if (!acceptsMessages(caseRecord)) {
                throw new ClosedConversationException(replyWindowDays);
            }

            // Checked before saving: with the new message already in, the streak is never empty and
            // the recipient would get one email per message instead of one per unread streak.
            boolean recipientAlreadyPending =
                    messageRepository.existsByCaseIdAndSenderRoleAndReadAtIsNull(caseId, party);

            CaseMessage saved = messageRepository.save(CaseMessage.builder()
                    .caseId(caseId)
                    .senderId(currentUserId())
                    .senderRole(party)
                    .body(body.trim())
                    .build());

            if (!recipientAlreadyPending) {
                notificationService.notifyNewMessage(caseRecord, party);
            }
            broadcast(caseId, saved);
            return CaseMessageResponse.from(saved, true);
        });
    }

    /** Best-effort: the message is saved already, and a broker failure must not fail the POST. */
    private void broadcast(Long caseId, CaseMessage saved) {
        try {
            messagingTemplate.convertAndSend(
                    CaseTopic.of(TenantContext.get(), caseId), CaseMessageEvent.from(saved));
        } catch (RuntimeException ex) {
            log.error("Could not push message {} of case {}", saved.getId(), caseId, ex);
        }
    }

    /** Explicit, so opening the case doesn't clear a badge the reader never looked at. */
    public void markRead(Long caseId, String insurerSlug) {
        tenantScope.forCase(caseId, insurerSlug, () -> {
            readableCase(caseId);
            StatusChangeActor party = accessPolicy.currentParty();
            if (!isParty(party)) {
                return null;
            }
            List<CaseMessage> incoming = messageRepository
                    .findByCaseIdAndSenderRoleAndReadAtIsNull(caseId, other(party));
            Instant now = clock.instant();
            incoming.forEach(message -> message.setReadAt(now));
            messageRepository.saveAll(incoming);
            return null;
        });
    }

    /**
     * Open while the case is live, and for {@code replyWindowDays} after it was resolved. Dated by
     * the transition into the current state and not by {@code updatedAt}, which any later write
     * (a sync, a reclassification) would push forward.
     */
    private boolean acceptsMessages(Case caseRecord) {
        if (!caseRecord.getCurrentStatus().isFinal()) {
            return true;
        }
        Instant resolvedAt = statusHistoryRepository
                .findFirstByCaseIdAndFinalStatus_IdOrderByChangedAtDesc(
                        caseRecord.getId(), caseRecord.getCurrentStatus().getId())
                .map(CaseStatusHistory::getChangedAt)
                .orElse(caseRecord.getUpdatedAt());
        return Duration.between(resolvedAt, clock.instant()).toDays() < replyWindowDays;
    }

    private boolean isIncomingUnread(CaseMessage message, StatusChangeActor party) {
        return isParty(party) && message.getSenderRole() == other(party) && message.getReadAt() == null;
    }

    private boolean isParty(StatusChangeActor party) {
        return party == StatusChangeActor.INSURED || party == StatusChangeActor.ANALYST;
    }

    private StatusChangeActor other(StatusChangeActor party) {
        return party == StatusChangeActor.INSURED ? StatusChangeActor.ANALYST : StatusChangeActor.INSURED;
    }

    private Case readableCase(Long caseId) {
        Case entity = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
        accessPolicy.assertCanRead(entity);
        return entity;
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new AccessDeniedException("No hay sesión para atribuir el mensaje.");
        }
        return userRepository.findByEmail(authentication.getName())
                .map(User::getId)
                .orElseThrow(() -> new AccessDeniedException("La sesión no corresponde a un usuario."));
    }
}
