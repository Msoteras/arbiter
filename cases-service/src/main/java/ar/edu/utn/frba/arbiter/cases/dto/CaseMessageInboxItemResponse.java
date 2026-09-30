package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;

import java.time.Instant;

/**
 * One row of "todas mis conversaciones": a case plus its last message. The {@code lastMessage*}
 * fields are null for an insured's case nobody has written on yet; {@code insurerSlug}/{@code
 * insurerName} are only set for the insured, whose case ids repeat across insurers.
 */
public record CaseMessageInboxItemResponse(
        Long caseId,
        String insurerSlug,
        String insurerName,
        String insuredName,
        String analystName,
        String branch,
        String claimCause,
        CaseStatus status,
        String lastMessageBody,
        String lastMessageSender,
        Instant lastMessageAt,
        int unreadCount
) {}
