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
        CaseStatus status,
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
        String eventLocation,
        BigDecimal claimedAmount,
        Classification analysisClassification,
        double analysisConfidence,
        /** Empty on Fast Track or before classification. */
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
}
