package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;

import java.time.Instant;

/** One row of "todas mis conversaciones": a case plus its last message. */
public record CaseMessageInboxItemResponse(
        Long caseId,
        String insuredName,
        String branch,
        String claimCause,
        CaseStatus status,
        String lastMessageBody,
        String lastMessageSender,
        Instant lastMessageAt,
        int unreadCount
) {}
