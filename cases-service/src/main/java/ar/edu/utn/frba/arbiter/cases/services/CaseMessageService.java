package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.dto.CaseMessageEvent;
import ar.edu.utn.frba.arbiter.cases.dto.CaseMessageInboxItemResponse;
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
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The conversation between an analyst and an insured about one case.
 *
 * <p>Deliberately outside the case's lifecycle: a message never moves the case. Asking for a
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
    private final InsuredCaseAggregator insuredCases;
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

    /**
     * The "todas mis conversaciones" list, most recent activity first. For analyst and referente, one
     * row per case of their tenant with at least one message. For the insured, every own case across
     * all their insurers, written on or not: each claim is a conversation they can start.
     */
    public List<CaseMessageInboxItemResponse> inbox() {
        StatusChangeActor party = accessPolicy.currentParty();
        if (party == null) {
            return List.of();
        }
        return party == StatusChangeActor.INSURED ? insuredInbox() : tenantInbox(party);
    }

    private List<CaseMessageInboxItemResponse> insuredInbox() {
        List<InsuredCaseAggregator.InsuredCase> own = insuredCases.allOwnCases();
        Map<String, List<Long>> idsByInsurer = own.stream().collect(Collectors.groupingBy(
                InsuredCaseAggregator.InsuredCase::insurerSlug,
                Collectors.mapping(it -> it.caseRecord().getId(), Collectors.toList())));

        // Messages live in each insurer's schema, like the cases; ids only mean something within one.
        Map<String, Map<Long, List<CaseMessage>>> messages = new HashMap<>();
        idsByInsurer.forEach((slug, ids) -> messages.put(slug, tenantScope.forCase(ids.getFirst(), slug, () ->
                messageRepository.findByCaseIdInOrderByCreatedAtAsc(ids).stream()
                        .collect(Collectors.groupingBy(CaseMessage::getCaseId)))));

        // Sorted by last message; the stable sort keeps unwritten cases in newest-report order.
        return own.stream()
                .map(it -> toInboxItem(it.caseRecord(),
                        messages.get(it.insurerSlug()).getOrDefault(it.caseRecord().getId(), List.of()),
                        StatusChangeActor.INSURED, it.insurerSlug(), it.insurerName()))
                .sorted(Comparator.comparing(
                        (CaseMessageInboxItemResponse row) ->
                                row.lastMessageAt() == null ? Instant.MIN : row.lastMessageAt(),
                        Comparator.reverseOrder()))
                .toList();
    }

    private List<CaseMessageInboxItemResponse> tenantInbox(StatusChangeActor party) {
        Map<Long, List<CaseMessage>> byCase = messageRepository.findAllByOrderByCreatedAtAsc().stream()
                .collect(Collectors.groupingBy(CaseMessage::getCaseId));
        if (byCase.isEmpty()) {
            return List.of();
        }
        Map<Long, Case> readableCases = caseRepository.findAllById(byCase.keySet()).stream()
                .filter(accessPolicy::canRead)
                .collect(Collectors.toMap(Case::getId, c -> c));

        return byCase.entrySet().stream()
                .filter(entry -> readableCases.containsKey(entry.getKey()))
                .map(entry -> toInboxItem(readableCases.get(entry.getKey()), entry.getValue(), party, null, null))
                .sorted(Comparator.comparing(CaseMessageInboxItemResponse::lastMessageAt).reversed())
                .toList();
    }

    private CaseMessageInboxItemResponse toInboxItem(Case caseRecord, List<CaseMessage> messages,
                                                     StatusChangeActor party, String insurerSlug,
                                                     String insurerName) {
        CaseMessage last = messages.isEmpty() ? null : messages.getLast();
        int unread = isParty(party)
                ? (int) messages.stream().filter(m -> isIncomingUnread(m, party)).count()
                : 0;
        ClaimsAnalyst analyst = caseRecord.getAnalyst();
        return new CaseMessageInboxItemResponse(
                caseRecord.getId(),
                insurerSlug,
                insurerName,
                caseRecord.getInsured().fullName(),
                analyst == null ? null : analyst.getName() + " " + analyst.getSurname(),
                caseRecord.getClaimCause().getBranch().getName(),
                caseRecord.getClaimCause().getName(),
                caseRecord.getStatus(),
                last == null ? null : last.getBody(),
                last == null ? null : last.getSenderRole().name(),
                last == null ? null : last.getCreatedAt(),
                unread);
    }

    public CaseMessageResponse post(Long caseId, String insurerSlug, String body) {
        return tenantScope.forCase(caseId, insurerSlug, () -> {
            Case caseRecord = readableCase(caseId);
            StatusChangeActor party = accessPolicy.currentParty();
            if (!isParty(party)) {
                throw new AccessDeniedException("Only the insured and the analyst write on the case.");
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
            throw new AccessDeniedException("No session to attribute the message to.");
        }
        return userRepository.findByEmail(authentication.getName())
                .map(User::getId)
                .orElseThrow(() -> new AccessDeniedException("The session does not belong to a user."));
    }
}
