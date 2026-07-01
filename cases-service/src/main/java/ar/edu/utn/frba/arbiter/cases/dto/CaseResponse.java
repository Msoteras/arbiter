package ar.edu.utn.frba.arbiter.cases.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CaseResponse(
        Long id,
        String status,
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
        String analysisClassification,
        double analysisConfidence,
        String analysisDetail
) {
}
