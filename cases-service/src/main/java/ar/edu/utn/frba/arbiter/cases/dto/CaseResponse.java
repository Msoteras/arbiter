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
        /**
         * Only set in the insured's multi-insurer listing, since case ids repeat across schemas.
         * The slug, not the id, so no database key ends up in a URL.
         */
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
        /** Null until the first classification resolves it. */
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
        /** Derived, not persisted. */
        DeadlinePriority deadlinePriority,
        /**
         * Tells apart, within {@code PENDING_ANALYST_REVIEW}, a case waiting on the analyst from one
         * waiting on the referente. Listings only; null for the insured.
         */
        SettlementStatus settlementStatus,
        /** Null on list endpoints; only GET /{id} loads it. */
        List<StatusTransitionResponse> statusHistory,
        /** Only GET /{id} loads it. */
        List<DocumentAnalysisSummary> documentAnalyses,
        /**
         * Every hard rule evaluated, passes included; only GET /{id} loads it. Empty means no rule ran;
         * null means the results couldn't be read, and the screen says different things for each.
         */
        List<RuleResultResponse> ruleResults,
        /**
         * What the insurer DB answered at classification time, not today. Current policies are a
         * separate call ({@code GET /cases/{id}/insured-policies}).
         */
        PolicySnapshotResponse policySnapshot,
        /**
         * Only while in PENDING_REPAIR and only on GET /{id}. The one derivation detail the insured
         * sees; expert assessments never travel in any field they read.
         */
        RepairProviderResponse repairProvider
) {
}
