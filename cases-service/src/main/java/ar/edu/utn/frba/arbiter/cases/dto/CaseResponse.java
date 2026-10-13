package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.dto.RuleResultResponse;
import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.CauseConsistency;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.DeadlinePriority;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.common.enums.SettlementStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A null {@code riskScore}/{@code riskBand} means not scored, never a real {@code LOW}.
 * {@code forensicReport} is analyst-only.
 */
public record CaseResponse(
        Long id,
        /** Only in the insured's multi-insurer listing, since case ids repeat across schemas. */
        String insurerSlug,
        String insurerName,
        /** Null for the insured, who reads {@code insuredStatus}. */
        CaseStatus status,
        InsuredStatusResponse insuredStatus,
        String branch,
        String product,
        String claimCause,
        /** The coverage {@code policySnapshot.sumInsured} belongs to; a policy holds several. */
        String coverage,
        String insuredItem,
        String insuredId,
        String insuredName,
        /** Politically exposed person: a due-diligence fact, deliberately kept out of scoring and the prompt. */
        boolean pep,
        String policyNumber,
        String description,
        LocalDateTime eventDate,
        /** When the insured says they filed the police report; null when the claim has none. */
        LocalDateTime policeReportAt,
        String eventLocation,
        BigDecimal claimedAmount,
        Classification analysisClassification,
        /**
         * The rules engine decided, not the model. Needed because a coverage exclusion or prescription
         * shares its literal with a model recommendation.
         */
        boolean resolvedByRules,
        double analysisConfidence,
        /** Empty on a rules outcome or before classification. */
        List<String> analysisReasons,
        /** Null means "not evaluated", never {@code MATCHES}. */
        CauseConsistency causeConsistency,
        /** Only with CONTRADICTS. */
        String suggestedClaimCause,
        /** Verbatim quote backing the verdict. Only with CONTRADICTS. */
        String causeEvidence,
        Double riskScore,
        RiskBand riskBand,
        List<RiskBreakdownItem> riskBreakdown,
        ImageForensicReport forensicReport,
        /** {@code claims_analyst} id, local to the tenant schema. Null means unassigned. */
        Long assignedAnalystId,
        String assignedAnalystName,
        Instant createdAt,
        Instant updatedAt,
        /** Art. 56, Ley 17.418. */
        LocalDate responseDeadline,
        DeadlinePriority deadlinePriority,
        /** Waiting on the analyst or on the referente, both {@code PENDING_ANALYST_REVIEW}. Listings only. */
        SettlementStatus settlementStatus,
        /** Null on list endpoints; only GET /{id} loads it. */
        List<StatusTransitionResponse> statusHistory,
        /** Only GET /{id} loads it. */
        List<DocumentAnalysisSummary> documentAnalyses,
        /** Only on GET /{id}, passes included. Empty: no rule ran; null: they couldn't be read. */
        List<RuleResultResponse> ruleResults,
        /** What the insurer DB answered at classification time, not today. */
        PolicySnapshotResponse policySnapshot,
        /** Only on GET /{id} while in PENDING_REPAIR: the one derivation detail the insured sees. */
        RepairProviderResponse repairProvider
) {

    /**
     * What the insured may read of their own case: the claim as they filed it, its status as they
     * are told it and who handles it. The model's recommendation, the fraud score, the rule trail,
     * the analyst's notes and the internal status are the insurer's.
     */
    public CaseResponse forInsured() {
        return new CaseResponse(
                id, insurerSlug, insurerName, null, insuredStatus, branch, product, claimCause, coverage,
                insuredItem, insuredId, insuredName, pep, policyNumber, description, eventDate,
                policeReportAt, eventLocation, claimedAmount,
                null, false, 0.0, List.of(), null, null, null,
                null, null, List.of(), null,
                assignedAnalystId, assignedAnalystName, createdAt, updatedAt, responseDeadline,
                deadlinePriority,
                null,
                statusHistory == null ? null
                        : statusHistory.stream()
                                .filter(StatusTransitionResponse::visibleToInsured)
                                .map(StatusTransitionResponse::forInsured)
                                .toList(),
                List.of(), List.of(), null,
                repairProvider);
    }
}
