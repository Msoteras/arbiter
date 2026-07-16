package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public record CaseResponse(
        Long id,
        CaseStatus status,
        String branch,
        String product,
        String claimCause,
        String insuredItem,
        String insuredId,
        String policyNumber,
        String description,
        LocalDateTime eventDate,
        String eventLocation,
        BigDecimal claimedAmount,
        Classification analysisClassification,
        double analysisConfidence,
        String analysisDetail,
        Instant createdAt,
        Instant updatedAt,
        /** Full transition trail with timestamps; null on list endpoints (only GET /{id} loads it). */
        List<StatusTransitionResponse> statusHistory
) {
}
