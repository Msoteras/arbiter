package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Expediente detail read model. The risk fields are the cached parallel fraud score: {@code
 * riskScore} and {@code riskBand} are {@code null} when the claim wasn't scored ("sin scorear"),
 * which the fraud-gauge renders as "Sin datos" — distinct from a real {@code LOW}.
 */
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
        Double riskScore,
        RiskBand riskBand,
        List<RiskBreakdownItem> riskBreakdown
) {
}
