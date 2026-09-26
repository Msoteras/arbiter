package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.RepairOutcome;
import ar.edu.utn.frba.arbiter.cases.dto.PendingSettlementResponse;
import ar.edu.utn.frba.arbiter.cases.dto.AuthorizedSettlementResponse;
import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementResponse;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementSuggestionTarget;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidSettlementException;
import ar.edu.utn.frba.arbiter.cases.exceptions.SettlementNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseSettlement;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicySnapshot;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentAnalysisRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseSettlementRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ExpertAssessmentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerReferentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyCoverageRepository;
import ar.edu.utn.frba.arbiter.common.enums.SettlementBasis;
import ar.edu.utn.frba.arbiter.common.enums.SettlementFormula;
import ar.edu.utn.frba.arbiter.common.enums.SettlementStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@link #forCase} proposes: it recomputes from frozen inputs and writes nothing. {@link #confirm}
 * records, once, inside the approval: what the analyst signed is what gets stored.
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    /** Explanation lines are read by an analyst, not parsed. */
    private static final Locale AR = Locale.forLanguageTag("es-AR");

    private static final BigDecimal FULL_PERCENTAGE = new BigDecimal("100.00");

    /** Insured document types that carry an amount usable for settling. */
    private static final String REPAIR_QUOTE = "repair_quote";
    private static final String PURCHASE_PROOF = "purchase_proof";
    /** Provider reports uploaded by the analyst, not insured attachments. */
    private static final String EXPERT_REPORT = "expert_report";
    private static final String REPAIR_REPORT = "repair_report";

    private final CaseRepository caseRepository;
    private final CaseSettlementRepository settlementRepository;
    private final SettlementCalculator calculator;
    private final SettlementAuthorityService authorityService;
    private final PolicyCoverageRepository policyCoverageRepository;
    private final CaseDocumentAnalysisRepository documentAnalysisRepository;
    private final ExpertAssessmentRepository expertAssessmentRepository;
    private final InsurerReferentRepository insurerReferentRepository;

    /**
     * The confirmed settlement if there is one, otherwise a proposal.
     *
     * @param replacementValue previews the effect of accrediting a value; ignored once a settlement
     *                         exists, since a signed amount doesn't move by opening the screen
     */
    @Transactional(readOnly = true)
    public SettlementResponse forCase(Long caseId, BigDecimal replacementValue) {
        Case caseRecord = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        Optional<CaseSettlement> confirmed = settlementRepository.findByCaseId(caseId);
        if (confirmed.isPresent()) {
            return toResponse(confirmed.get(), true, List.of(), caseRecord.getCoverage(), null);
        }

        PolicySnapshot snapshot = caseRepository.findPolicySnapshot(caseId).orElse(null);
        Coverage coverage = caseRecord.getCoverage();
        CaseSettlement proposal = calculator.calculate(
                caseRecord, coverage, policyCoverageOf(caseRecord), snapshot, replacementValue,
                formulaFor(caseId, coverage));
        // So the analyst sees before signing that the amount will need the referent.
        proposal.setAuthorityLimit(authorityService.limitFor(branchIdOf(caseRecord)));
        return toResponse(proposal, false,
                warnings(coverage, snapshot, replacementValue, proposal.getFormula()), coverage,
                suggestionFor(caseId, coverage));
    }

    /**
     * Runs inside the approval's transaction: a settlement for a case that never got approved would
     * be unaccountable. Recalculates rather than trusting the client: only {@code settledAmount} is
     * the analyst's decision, so a tampered or stale proposal can't rewrite the audit trail.
     *
     * @return {@link SettlementStatus#AUTHORIZED} if the caller can resolve the case now;
     *         {@link SettlementStatus#PENDING_AUTHORIZATION} if the referent has to sign first
     */
    @Transactional
    public CaseSettlement confirm(Case caseRecord, Long analystId, String justification,
                                  SettlementDecisionRequest request) {
        if (request == null) {
            throw InvalidSettlementException.missing();
        }

        PolicySnapshot snapshot = caseRepository.findPolicySnapshot(caseRecord.getId()).orElse(null);
        CaseSettlement settlement = calculator.calculate(caseRecord, caseRecord.getCoverage(),
                policyCoverageOf(caseRecord), snapshot, request.replacementValue(),
                formulaFor(caseRecord.getId(), caseRecord.getCoverage()));

        BigDecimal authorized = request.settledAmount();
        if (authorized.compareTo(settlement.getSumInsured()) > 0) {
            throw InvalidSettlementException.aboveSumInsured(authorized, settlement.getSumInsured());
        }
        boolean adjusted = authorized.compareTo(settlement.getCalculatedAmount()) != 0;
        if (adjusted && (request.adjustmentReason() == null || request.adjustmentReason().isBlank())) {
            throw InvalidSettlementException.adjustmentWithoutReason();
        }

        // One settlement per case: re-confirming (e.g. after a referent return) must update the
        // existing row, or the insert would violate case_settlement_case_unique.
        settlementRepository.findByCaseId(caseRecord.getId())
                .ifPresent(existing -> settlement.setId(existing.getId()));

        settlement.setSettledAmount(authorized);
        // Null when not adjusted, so it never reads as an override later.
        settlement.setAdjustmentReason(adjusted ? request.adjustmentReason().trim() : null);
        settlement.setAnalystId(analystId);
        settlement.setConfirmedAt(Instant.now());

        applyAuthority(settlement, caseRecord, authorized, justification);
        return settlementRepository.save(settlement);
    }

    /**
     * Within the branch ceiling the analyst's signature is the authorization; above it the
     * settlement waits for the referent. The limit is frozen on the row either way, including when
     * there is none, since "no ceiling" and "ceiling was X" are different facts later.
     */
    private void applyAuthority(CaseSettlement settlement, Case caseRecord,
                                BigDecimal authorized, String justification) {
        BigDecimal limit = authorityService.limitFor(branchIdOf(caseRecord));
        settlement.setAuthorityLimit(limit);
        // Re-confirming answers a previous return, so the old reason must not linger.
        settlement.setReturnReason(null);
        settlement.setAuthorizedByUserId(null);
        settlement.setAuthorizedAt(null);

        boolean needsReferent = limit != null && authorized.compareTo(limit) > 0;
        settlement.setStatus(needsReferent
                ? SettlementStatus.PENDING_AUTHORIZATION
                : SettlementStatus.AUTHORIZED);
        // Held until the referent authorizes, when the decision is forwarded with the analyst's own justification.
        settlement.setPendingJustification(needsReferent ? justification : null);
    }

    /**
     * This policy's terms for the case's coverage; only a fallback for the snapshot. Null if the
     * coverage isn't synced yet.
     */
    private PolicyCoverage policyCoverageOf(Case caseRecord) {
        if (caseRecord.getPolicy() == null || caseRecord.getCoverage() == null) {
            return null;
        }
        return policyCoverageRepository
                .findByPolicyIdAndCoverageId(caseRecord.getPolicy().getId(), caseRecord.getCoverage().getId())
                .orElse(null);
    }

    /**
     * An amount on file, offered as a suggestion only. Provider valuations beat the insured's documents
     * and the latest wins; the document depends on the formula (repair quote, or purchase proof for a
     * total loss). With nothing to accredit, only the expert's indemnifiable amount is proposed.
     */
    private Suggestion suggestionFor(Long caseId, Coverage coverage) {
        // Newest first: each new valuation supersedes the previous one.
        List<ExpertAssessment> valuations = expertAssessmentRepository
                .findByCaseIdOrderByDerivedAtDesc(caseId).stream()
                .filter(assessment -> assessment.getReportReceivedAt() != null)
                .filter(assessment -> valuationOf(assessment) != null
                        && valuationOf(assessment).signum() > 0)
                .sorted(Comparator.comparing(ExpertAssessment::getReportReceivedAt).reversed())
                .toList();

        String wanted = accreditedDocumentFor(coverage);
        if (wanted == null) {
            // A repair cost is not an opinion on what to pay, so only the expert's amount applies.
            return valuations.stream()
                    .filter(assessment -> assessment.getProviderType() == ProviderType.ESTUDIO_LIQUIDADOR)
                    .findFirst()
                    .map(assessment -> new Suggestion(assessment.getIndemnifiableAmount(),
                            EXPERT_REPORT, SettlementSuggestionTarget.SETTLED_AMOUNT))
                    .orElse(null);
        }

        if (!valuations.isEmpty()) {
            ExpertAssessment latest = valuations.getFirst();
            return new Suggestion(valuationOf(latest), sourceOf(latest),
                    SettlementSuggestionTarget.ACCREDITED_AMOUNT);
        }

        return documentAnalysisRepository.findByCaseId(caseId).stream()
                .filter(doc -> wanted.equals(doc.documentType()))
                .filter(doc -> doc.amount() != null && doc.amount().signum() > 0)
                .findFirst()
                .map(doc -> new Suggestion(doc.amount(), doc.documentType(),
                        SettlementSuggestionTarget.ACCREDITED_AMOUNT))
                .orElse(null);
    }

    /** One query for the whole inbox page. Cases without a settlement are absent from the map. */
    public Map<Long, SettlementStatus> statusesFor(Collection<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return Map.of();
        }
        return settlementRepository.findByCaseIdIn(caseIds).stream()
                .filter(settlement -> settlement.getStatus() != null)
                .collect(Collectors.toMap(CaseSettlement::getCaseId, CaseSettlement::getStatus));
    }

    /**
     * A repair coverage assumes the item survived. When the repair shop declares it irreparable the
     * item is gone, as if stolen, and it settles as a total loss instead.
     */
    private SettlementFormula formulaFor(Long caseId, Coverage coverage) {
        SettlementFormula configured = coverage.getSettlementFormula() == null
                ? SettlementFormula.TOTAL_LOSS
                : coverage.getSettlementFormula();
        if (configured != SettlementFormula.REPAIR || !declaredIrreparable(caseId)) {
            return configured;
        }
        return SettlementFormula.TOTAL_LOSS;
    }

    /** From the returned repair report, not an analyst assumption. */
    private boolean declaredIrreparable(Long caseId) {
        return expertAssessmentRepository.findByCaseIdOrderByDerivedAtDesc(caseId).stream()
                .filter(assessment -> assessment.getProviderType() == ProviderType.SERVICIO_TECNICO)
                .filter(assessment -> assessment.getReportReceivedAt() != null)
                .anyMatch(assessment -> assessment.getRepairOutcome() == RepairOutcome.IRREPARABLE);
    }

    /** The expert values the claim; the repair shop quotes the fix. Different columns. */
    private static BigDecimal valuationOf(ExpertAssessment assessment) {
        return assessment.getProviderType() == ProviderType.SERVICIO_TECNICO
                ? assessment.getRepairCost() : assessment.getIndemnifiableAmount();
    }

    private static String sourceOf(ExpertAssessment assessment) {
        return assessment.getProviderType() == ProviderType.SERVICIO_TECNICO
                ? REPAIR_REPORT : EXPERT_REPORT;
    }

    /** Null when the coverage doesn't ask for an accredited amount. */
    private String accreditedDocumentFor(Coverage coverage) {
        if (coverage.getSettlementFormula() == SettlementFormula.REPAIR) {
            return REPAIR_QUOTE;
        }
        if (coverage.getSettlementBasis() == SettlementBasis.LESSER_OF_SUM_AND_REPLACEMENT) {
            return PURCHASE_PROOF;
        }
        return null;
    }

    private record Suggestion(BigDecimal amount, String documentType,
                              SettlementSuggestionTarget target) {}

    /** The branch hangs off the claim cause — {@code cases} has no column of its own for it. */
    private Long branchIdOf(Case caseRecord) {
        if (caseRecord.getClaimCause() == null || caseRecord.getClaimCause().getBranch() == null) {
            return null;
        }
        return caseRecord.getClaimCause().getBranch().getId();
    }

    /** Oldest first: a decided claim is burning the 30-day legal term while it waits. */
    @Transactional(readOnly = true)
    public List<PendingSettlementResponse> pendingAuthorization() {
        return settlementRepository
                .findByStatusOrderByConfirmedAtAsc(SettlementStatus.PENDING_AUTHORIZATION)
                .stream()
                .flatMap(settlement -> caseRepository.findById(settlement.getCaseId())
                        .map(caseRecord -> toPending(settlement, caseRecord))
                        .stream())
                .toList();
    }

    private PendingSettlementResponse toPending(CaseSettlement settlement, Case caseRecord) {
        BigDecimal limit = settlement.getAuthorityLimit();
        return new PendingSettlementResponse(
                caseRecord.getId(),
                fullName(caseRecord),
                branchNameOf(caseRecord),
                caseRecord.getClaimCause() == null ? null : caseRecord.getClaimCause().getName(),
                analystNameOf(caseRecord),
                settlement.getCalculatedAmount(),
                settlement.getSettledAmount(),
                settlement.getAdjustmentReason(),
                limit,
                // The excess over the limit is what the referent is judging.
                limit == null ? null : settlement.getSettledAmount().subtract(limit),
                settlement.getConfirmedAt(),
                settlement.getConfirmedAt() == null
                        ? 0
                        : ChronoUnit.DAYS.between(settlement.getConfirmedAt(), Instant.now()));
    }

    /** Most recent first. */
    @Transactional(readOnly = true)
    public List<AuthorizedSettlementResponse> authorizedByReferent() {
        List<CaseSettlement> settlements = settlementRepository
                .findTop50ByStatusAndAuthorizedAtIsNotNullOrderByAuthorizedAtDesc(
                        SettlementStatus.AUTHORIZED);
        Map<Long, Case> cases = caseRepository
                .findAllById(settlements.stream().map(CaseSettlement::getCaseId).toList())
                .stream()
                .collect(Collectors.toMap(Case::getId, c -> c));
        Map<Long, String> referentNames = insurerReferentRepository
                .findByUser_IdIn(settlements.stream()
                        .map(CaseSettlement::getAuthorizedByUserId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(r -> r.getUser().getId(),
                        r -> (r.getName() + " " + r.getSurname()).trim()));
        return settlements.stream()
                .filter(settlement -> cases.containsKey(settlement.getCaseId()))
                .map(settlement -> toAuthorized(settlement, cases.get(settlement.getCaseId()),
                        referentNames.get(settlement.getAuthorizedByUserId())))
                .toList();
    }

    private AuthorizedSettlementResponse toAuthorized(CaseSettlement settlement, Case caseRecord,
                                                      String authorizedByName) {
        BigDecimal limit = settlement.getAuthorityLimit();
        return new AuthorizedSettlementResponse(
                caseRecord.getId(),
                fullName(caseRecord),
                branchNameOf(caseRecord),
                caseRecord.getClaimCause() == null ? null : caseRecord.getClaimCause().getName(),
                analystNameOf(caseRecord),
                settlement.getCalculatedAmount(),
                settlement.getSettledAmount(),
                settlement.getAdjustmentReason(),
                limit,
                limit == null ? null : settlement.getSettledAmount().subtract(limit),
                settlement.getConfirmedAt(),
                settlement.getAuthorizedAt(),
                authorizedByName);
    }

    private String fullName(Case caseRecord) {
        if (caseRecord.getInsured() == null) {
            return null;
        }
        return (caseRecord.getInsured().getName() + " " + caseRecord.getInsured().getSurname()).trim();
    }

    private String analystNameOf(Case caseRecord) {
        if (caseRecord.getAnalyst() == null) {
            return null;
        }
        return (caseRecord.getAnalyst().getName() + " " + caseRecord.getAnalyst().getSurname()).trim();
    }

    private String branchNameOf(Case caseRecord) {
        if (caseRecord.getClaimCause() == null || caseRecord.getClaimCause().getBranch() == null) {
            return null;
        }
        return caseRecord.getClaimCause().getBranch().getName();
    }

    @Transactional(readOnly = true)
    public CaseSettlement require(Long caseId) {
        return settlementRepository.findByCaseId(caseId)
                .orElseThrow(() -> new SettlementNotFoundException(caseId));
    }

    /** Only marks the settlement; resolving the case belongs to the case lifecycle. */
    @Transactional
    public CaseSettlement markAuthorized(Long caseId, Long referentUserId) {
        CaseSettlement settlement = require(caseId);
        if (settlement.getStatus() != SettlementStatus.PENDING_AUTHORIZATION) {
            throw InvalidSettlementException.notPendingAuthorization(settlement.getStatus());
        }
        settlement.setStatus(SettlementStatus.AUTHORIZED);
        settlement.setAuthorizedByUserId(referentUserId);
        settlement.setAuthorizedAt(Instant.now());
        // The justification now lives in case_classification with the recorded decision.
        settlement.setPendingJustification(null);
        return settlementRepository.save(settlement);
    }

    /** Not a rejection of the claim: the analyst keeps the case and can settle it again. */
    @Transactional
    public CaseSettlement returnToAnalyst(Long caseId, Long referentUserId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw InvalidSettlementException.returnWithoutReason();
        }
        CaseSettlement settlement = require(caseId);
        if (settlement.getStatus() != SettlementStatus.PENDING_AUTHORIZATION) {
            throw InvalidSettlementException.notPendingAuthorization(settlement.getStatus());
        }
        settlement.setStatus(SettlementStatus.RETURNED);
        settlement.setReturnReason(reason.trim());
        settlement.setAuthorizedByUserId(referentUserId);
        settlement.setAuthorizedAt(Instant.now());
        return settlementRepository.save(settlement);
    }

    /**
     * Caveats about the calculation as a whole; a deduction that came out at zero is explained on
     * its own line instead. None of them block: the analyst can still adjust and justify.
     */
    private List<String> warnings(Coverage coverage, PolicySnapshot snapshot,
                                  BigDecimal replacementValue, SettlementFormula formula) {
        List<String> warnings = new ArrayList<>();

        if (snapshot == null) {
            warnings.add("El expediente no tiene póliza consultada: la suma asegurada sale de la copia "
                    + "local de la póliza, que pudo actualizarse después de la denuncia.");
        }
        boolean accredited = replacementValue != null && replacementValue.signum() > 0;

        // Checked against the applied formula, not the coverage's: an irreparable item no longer
        // settles by repair.
        if (formula == SettlementFormula.REPAIR && !accredited) {
            warnings.add("La cobertura liquida por reparación y no hay presupuesto acreditado: sin él "
                    + "no hay monto que pagar. Cargá el presupuesto del expediente y recalculá.");
        }
        if (formula != SettlementFormula.REPAIR
                && coverage.getSettlementBasis() == SettlementBasis.LESSER_OF_SUM_AND_REPLACEMENT
                && !accredited) {
            warnings.add("La cobertura liquida por el menor entre la suma asegurada y el valor de "
                    + "reposición, pero no hay valor de reposición acreditado: se toma la suma asegurada.");
        }
        return warnings;
    }

    private SettlementResponse toResponse(CaseSettlement s, boolean confirmed, List<String> warnings,
                                          Coverage coverage, Suggestion suggestion) {
        return new SettlementResponse(
                s.getFormula(),
                s.getSumInsured(),
                s.getSettlementBasis(),
                s.getReplacementValue(),
                s.getDeductibleRate(),
                s.getEventOrdinal(),
                s.getEventPercentage(),
                s.getPendingInstallments(),
                s.getInstallmentAmount(),
                s.getDeductibleAmount(),
                s.getPendingInstallmentsAmount(),
                s.getOverdueBalanceAmount(),
                s.getCalculatedAmount(),
                s.getSettledAmount(),
                s.getAdjustmentReason(),
                confirmed,
                s.getConfirmedAt(),
                confirmed ? s.getStatus() : null,
                s.getAuthorityLimit(),
                s.getReturnReason(),
                suggestion == null ? null : suggestion.amount(),
                suggestion == null ? null : suggestion.documentType(),
                suggestion == null ? null : suggestion.target(),
                breakdown(s, confirmed, coverage),
                warnings);
    }

    /** Built here rather than in the SPA so the wording and the arithmetic can't drift apart. */
    private List<SettlementResponse.Line> breakdown(CaseSettlement s, boolean confirmed,
                                                    Coverage coverage) {
        List<SettlementResponse.Line> lines = new ArrayList<>();

        boolean repair = s.getFormula() == SettlementFormula.REPAIR;
        // A mismatch between the applied formula and the coverage's means the item was irreparable,
        // and a silent formula change must be explained to the analyst.
        boolean irreparable = !repair && coverage != null
                && coverage.getSettlementFormula() == SettlementFormula.REPAIR;
        boolean cappedByReplacement = !repair
                && s.getSettlementBasis() == SettlementBasis.LESSER_OF_SUM_AND_REPLACEMENT
                && s.getReplacementValue() != null
                && s.getReplacementValue().compareTo(s.getSumInsured()) < 0;

        if (repair) {
            // The quote is the ceiling of a repair, so the sheet starts there, not at the sum insured.
            lines.add(SettlementResponse.Line.base("Presupuesto de reparación",
                    s.getReplacementValue() == null || s.getReplacementValue().signum() <= 0
                            ? "sin presupuesto acreditado — no hay monto que pagar"
                            : "acreditado en el expediente · tope: la suma asegurada, %s"
                                    .formatted(money(s.getSumInsured())),
                    repairCeiling(s)));
        } else {
            lines.add(SettlementResponse.Line.base("Suma asegurada",
                    irreparable
                            ? "el servicio técnico declaró el equipo irreparable: se liquida como "
                                    + "pérdida total, no como reparación"
                            : null,
                    s.getSumInsured()));
            if (cappedByReplacement) {
                lines.add(SettlementResponse.Line.base("Valor de reposición acreditado",
                        "menor que la suma asegurada — se indemniza por éste (art. 7, Bases de Indemnización)",
                        s.getReplacementValue()));
            }
        }

        if (s.getEventPercentage() != null && s.getEventPercentage().compareTo(FULL_PERCENTAGE) != 0) {
            BigDecimal ceiling = repair ? repairCeiling(s)
                    : cappedByReplacement ? s.getReplacementValue() : s.getSumInsured();
            lines.add(SettlementResponse.Line.base(
                    "Tope por ser el %d.º evento del año".formatted(s.getEventOrdinal()),
                    "%s%% del techo".formatted(trimPercentage(s.getEventPercentage())),
                    percentageOf(ceiling, s.getEventPercentage())));
        }

        if (s.getDeductibleRate() != null && s.getDeductibleRate().signum() > 0) {
            lines.add(SettlementResponse.Line.deduction("Franquicia",
                    "%s%% de la suma asegurada (%s)".formatted(
                            trimPercentage(s.getDeductibleRate()), money(s.getSumInsured())),
                    s.getDeductibleAmount()));
        }

        // Enabled deductions are always shown, even at zero, with the reason next to the number.
        // Not on a repair, where the policy isn't extinguished and there is nothing to deduct.
        if (!repair && coverage != null && coverage.isDeductPendingInstallments()) {
            lines.add(SettlementResponse.Line.deduction("Cuotas a vencer",
                    pendingInstallmentsDetail(s), s.getPendingInstallmentsAmount()));
        } else if (irreparable) {
            // A damage coverage's switch was configured with repairs in mind; the zero line shows
            // the referent could enable it.
            lines.add(SettlementResponse.Line.deduction("Cuotas a vencer",
                    "esta cobertura no tiene configurado el descuento de cuotas — se paga sin él",
                    BigDecimal.ZERO));
        }

        if (coverage != null && coverage.isDeductOverdueBalance()) {
            lines.add(SettlementResponse.Line.deduction("Deuda vencida de la póliza",
                    s.getOverdueBalanceAmount().signum() > 0
                            ? "saldo impago del contrato (cláusula 102, art. 5)"
                            : "la póliza consultada no registra saldo impago",
                    s.getOverdueBalanceAmount()));
        }

        // An adjusted, signed settlement shows both amounts: what is paid is not the formula's.
        boolean adjusted = confirmed && s.getSettledAmount() != null
                && s.getSettledAmount().compareTo(s.getCalculatedAmount()) != 0;
        if (adjusted) {
            lines.add(SettlementResponse.Line.total("Monto calculado", s.getCalculatedAmount()));
            lines.add(SettlementResponse.Line.total("Monto autorizado", s.getSettledAmount()));
        } else {
            lines.add(SettlementResponse.Line.total("Monto a pagar", s.getCalculatedAmount()));
        }
        return lines;
    }

    /**
     * "No instalments left" is a result; "no instalment amount" is a calculation that couldn't be
     * done, which the analyst can make up for by adjusting.
     */
    private String pendingInstallmentsDetail(CaseSettlement s) {
        if (s.getInstallmentAmount() == null) {
            return "la póliza consultada no trae el importe de cuota — no se descontó nada";
        }
        if (s.getPendingInstallments() == 0) {
            return "no quedan cuotas por vencer";
        }
        return "%d cuota(s) × %s — la pérdida total extingue la póliza"
                .formatted(s.getPendingInstallments(), money(s.getInstallmentAmount()));
    }

    private BigDecimal repairCeiling(CaseSettlement s) {
        if (s.getReplacementValue() == null || s.getReplacementValue().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return s.getSumInsured().min(s.getReplacementValue());
    }

    private BigDecimal percentageOf(BigDecimal amount, BigDecimal percentagePoints) {
        return amount.multiply(percentagePoints)
                .divide(FULL_PERCENTAGE, 2, java.math.RoundingMode.HALF_UP);
    }

    /** "10.00" reads as 10 and "12.50" as 12,5 — trailing zeros are noise in a label. */
    private String trimPercentage(BigDecimal percentagePoints) {
        return NumberFormat.getNumberInstance(AR).format(percentagePoints.stripTrailingZeros());
    }

    private String money(BigDecimal amount) {
        return amount == null ? "—" : NumberFormat.getCurrencyInstance(AR).format(amount);
    }
}
