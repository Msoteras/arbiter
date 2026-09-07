package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.PendingSettlementResponse;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidSettlementException;
import ar.edu.utn.frba.arbiter.cases.exceptions.SettlementNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseSettlement;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicySnapshot;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseSettlementRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Determining the amount to pay, which the insurer's procedure treats as its own step of the
 * analyst's job (NSIN001 §5.2.1.2) and Arbiter used to skip: a case reached APPROVED without ever
 * saying how much.
 *
 * <p>Two halves, and the split is the point. {@link #forCase} <b>proposes</b>: it recomputes the
 * arithmetic from inputs that are already frozen, writes nothing, and can be called as many times
 * as the analyst wants while they try a replacement value. {@link #confirm} <b>records</b>: it
 * runs once, inside the approval, and is the only thing that persists.
 *
 * <p>Human-in-the-loop, same as decision #5: the calculation never settles anything on its own.
 * The analyst confirms the proposal or moves it with a stated reason, and either way what they
 * signed is what gets stored.
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    /** Argentine grouping in the explanation lines — it's read by an analyst, not parsed. */
    private static final Locale AR = Locale.forLanguageTag("es-AR");

    private static final BigDecimal FULL_PERCENTAGE = new BigDecimal("100.00");

    private final CaseRepository caseRepository;
    private final CaseSettlementRepository settlementRepository;
    private final SettlementCalculator calculator;
    private final SettlementAuthorityService authorityService;
    private final PolicyCoverageRepository policyCoverageRepository;

    /**
     * What this case pays. The settlement already authorized if there is one, otherwise the
     * proposal for the analyst to confirm.
     *
     * @param replacementValue lets the analyst preview the effect of accrediting a replacement
     *                         value before committing to it. Ignored once a settlement exists —
     *                         a signed amount doesn't move because someone opened the screen
     */
    @Transactional(readOnly = true)
    public SettlementResponse forCase(Long caseId, BigDecimal replacementValue) {
        Case caseRecord = caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));

        Optional<CaseSettlement> confirmed = settlementRepository.findByCaseId(caseId);
        if (confirmed.isPresent()) {
            return toResponse(confirmed.get(), true, List.of(), caseRecord.getCoverage());
        }

        PolicySnapshot snapshot = caseRepository.findPolicySnapshot(caseId).orElse(null);
        Coverage coverage = caseRecord.getCoverage();
        CaseSettlement proposal = calculator.calculate(
                caseRecord, coverage, policyCoverageOf(caseRecord), snapshot, replacementValue);
        // El tope vigente, para que el analista vea ANTES de firmar que este monto va a necesitar
        // al referente. Enterarse recién al confirmar es enterarse tarde.
        proposal.setAuthorityLimit(authorityService.limitFor(branchIdOf(caseRecord)));
        return toResponse(proposal, false, warnings(coverage, snapshot, replacementValue), coverage);
    }

    /**
     * Records the amount the analyst authorized, and decides whether their signature is enough.
     * Called from within the approval, in its transaction: determining the amount and resolving
     * the claim are one act, and a settlement stored against a case that never got approved would
     * be a row nobody could account for.
     *
     * <p>Recalculates rather than trusting the client's arithmetic. The submitted
     * {@code settledAmount} is the analyst's decision and is honored as given; everything around
     * it — the ceiling, the deductions, what the formula produced — is worked out here, so a
     * tampered or merely stale proposal can't rewrite the audit trail.
     *
     * @return the stored settlement. {@link SettlementStatus#AUTHORIZED} means the caller can go
     *         ahead and resolve the case; {@link SettlementStatus#PENDING_AUTHORIZATION} means the
     *         amount is over the branch's attribution and the referente has to sign first
     */
    @Transactional
    public CaseSettlement confirm(Case caseRecord, Long analystId, String justification,
                                  SettlementDecisionRequest request) {
        if (request == null) {
            throw InvalidSettlementException.missing();
        }

        PolicySnapshot snapshot = caseRepository.findPolicySnapshot(caseRecord.getId()).orElse(null);
        CaseSettlement settlement = calculator.calculate(caseRecord, caseRecord.getCoverage(),
                policyCoverageOf(caseRecord), snapshot, request.replacementValue());

        BigDecimal authorized = request.settledAmount();
        if (authorized.compareTo(settlement.getSumInsured()) > 0) {
            throw InvalidSettlementException.aboveSumInsured(authorized, settlement.getSumInsured());
        }
        boolean adjusted = authorized.compareTo(settlement.getCalculatedAmount()) != 0;
        if (adjusted && (request.adjustmentReason() == null || request.adjustmentReason().isBlank())) {
            throw InvalidSettlementException.adjustmentWithoutReason();
        }

        // Reusar la fila que ya está, si la hay: la liquidación es una por expediente, y el
        // analista puede volver a confirmarla —después de que el referente se la devolvió, o
        // porque se corrigió—. Sin heredar el id, el save intentaría insertar y chocaría contra
        // case_settlement_case_unique.
        settlementRepository.findByCaseId(caseRecord.getId())
                .ifPresent(existing -> settlement.setId(existing.getId()));

        settlement.setSettledAmount(authorized);
        // Null when it matches: a reason attached to an amount that wasn't adjusted reads, later,
        // as if something had been overridden.
        settlement.setAdjustmentReason(adjusted ? request.adjustmentReason().trim() : null);
        settlement.setAnalystId(analystId);
        settlement.setConfirmedAt(Instant.now());

        applyAuthority(settlement, caseRecord, authorized, justification);
        return settlementRepository.save(settlement);
    }

    /**
     * Anexo II: an analyst approves settlements "hasta el límite del atributo asignado por rama".
     * Within the ceiling their signature is the authorization and there is nobody else to record;
     * over it, the settlement waits for the referente.
     *
     * <p>The limit is frozen onto the row either way, including when there is none. "Nobody set a
     * ceiling for this branch" and "the ceiling was X" are different facts, and six months from
     * now only the stored one can tell them apart.
     */
    private void applyAuthority(CaseSettlement settlement, Case caseRecord,
                                BigDecimal authorized, String justification) {
        BigDecimal limit = authorityService.limitFor(branchIdOf(caseRecord));
        settlement.setAuthorityLimit(limit);
        // Se limpia siempre: si el referente la había devuelto, volver a confirmarla es
        // justamente responder a esa devolución, y dejar el motivo viejo la haría ver rechazada.
        settlement.setReturnReason(null);
        settlement.setAuthorizedByUserId(null);
        settlement.setAuthorizedAt(null);

        boolean needsReferente = limit != null && authorized.compareTo(limit) > 0;
        settlement.setStatus(needsReferente
                ? SettlementStatus.PENDING_AUTHORIZATION
                : SettlementStatus.AUTHORIZED);
        // En custodia mientras espera: la decisión todavía no se registró, y cuando el referente
        // autorice hay que reenviarla con la justificación que escribió el analista, no una nueva.
        settlement.setPendingJustification(needsReferente ? justification : null);
    }

    /**
     * Los términos que ESTA póliza contrató para la cobertura del expediente: su suma asegurada y
     * su franquicia. Una póliza no tiene una suma asegurada sola —cubre robo y hurto con montos
     * distintos—, así que el par (póliza, cobertura) es lo que identifica el número.
     *
     * <p>Solo se usa como respaldo del snapshot, que es lo que la aseguradora respondió cuando se
     * denunció el siniestro. Null si la póliza todavía no sincronizó esa cobertura.
     */
    private PolicyCoverage policyCoverageOf(Case caseRecord) {
        if (caseRecord.getPolicy() == null || caseRecord.getCoverage() == null) {
            return null;
        }
        return policyCoverageRepository
                .findByPolicyIdAndCoverageId(caseRecord.getPolicy().getId(), caseRecord.getCoverage().getId())
                .orElse(null);
    }

    /** The branch hangs off the claim cause — {@code cases} has no column of its own for it. */
    private Long branchIdOf(Case caseRecord) {
        if (caseRecord.getClaimCause() == null || caseRecord.getClaimCause().getBranch() == null) {
            return null;
        }
        return caseRecord.getClaimCause().getBranch().getId();
    }

    /**
     * Every settlement waiting for the referente, oldest first — the queue their screen shows.
     * Oldest first because a claim already decided by its analyst is burning the 30-day legal
     * window while it waits, so the one that has waited longest is the one to sign.
     */
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
                // El excedente, ya restado: es el número que el referente está juzgando, y hacérselo
                // calcular en cada fila es como se deja de leer una bandeja.
                limit == null ? null : settlement.getSettledAmount().subtract(limit),
                settlement.getConfirmedAt(),
                settlement.getConfirmedAt() == null
                        ? 0
                        : ChronoUnit.DAYS.between(settlement.getConfirmedAt(), Instant.now()));
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

    /** The case's settlement, or a 404 — used by the actions that act on an existing one. */
    @Transactional(readOnly = true)
    public CaseSettlement require(Long caseId) {
        return settlementRepository.findByCaseId(caseId)
                .orElseThrow(() -> new SettlementNotFoundException(caseId));
    }

    /**
     * The referente signs off on an amount above the analyst's attribution. Only marks the
     * settlement: resolving the case is the caller's job, because forwarding the decision and
     * moving the expediente is the case lifecycle's business, not the money's.
     */
    @Transactional
    public CaseSettlement markAuthorized(Long caseId, Long referentUserId) {
        CaseSettlement settlement = require(caseId);
        if (settlement.getStatus() != SettlementStatus.PENDING_AUTHORIZATION) {
            throw InvalidSettlementException.notPendingAuthorization(settlement.getStatus());
        }
        settlement.setStatus(SettlementStatus.AUTHORIZED);
        settlement.setAuthorizedByUserId(referentUserId);
        settlement.setAuthorizedAt(Instant.now());
        // Ya cumplió: la decisión se está registrando ahora, y a partir de acá la justificación
        // vive en case_classification. Dejarla acá sería la misma frase guardada dos veces.
        settlement.setPendingJustification(null);
        return settlementRepository.save(settlement);
    }

    /**
     * The referente sends it back with a reason. Not a rejection of the claim — the analyst keeps
     * the case and can settle it again, at another amount or the same one better argued. A control
     * that can only say yes is not a control.
     */
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
     * What the analyst should know before signing, and that the settlement sheet can't say on its
     * own. A deduction that came out at zero is <b>not</b> here: that belongs on its own line of
     * the sheet, next to the arithmetic it explains. What's left is about the calculation as a
     * whole — that it ran on data this claim never saw, or that the ceiling silently fell back.
     *
     * <p>None of it blocks: a missing input makes the proposal weaker, not wrong, and the analyst
     * can settle anyway — that's what the adjustment and its justification are for.
     */
    private List<String> warnings(Coverage coverage, PolicySnapshot snapshot, BigDecimal replacementValue) {
        List<String> warnings = new ArrayList<>();

        if (snapshot == null) {
            warnings.add("El expediente no tiene póliza consultada: la suma asegurada sale de la copia "
                    + "local de la póliza, que pudo actualizarse después de la denuncia.");
        }
        boolean accredited = replacementValue != null && replacementValue.signum() > 0;

        if (coverage.getSettlementFormula() == SettlementFormula.REPAIR && !accredited) {
            // Es la única advertencia que describe una propuesta en cero, no una deducción que no
            // se pudo hacer: sin presupuesto la reparación no tiene qué pagar.
            warnings.add("La cobertura liquida por reparación y no hay presupuesto acreditado: sin él "
                    + "no hay monto que pagar. Cargá el presupuesto del expediente y recalculá.");
        }
        if (coverage.getSettlementFormula() != SettlementFormula.REPAIR
                && coverage.getSettlementBasis() == SettlementBasis.LESSER_OF_SUM_AND_REPLACEMENT
                && !accredited) {
            warnings.add("La cobertura liquida por el menor entre la suma asegurada y el valor de "
                    + "reposición, pero no hay valor de reposición acreditado: se toma la suma asegurada.");
        }
        return warnings;
    }

    private SettlementResponse toResponse(CaseSettlement s, boolean confirmed, List<String> warnings,
                                          Coverage coverage) {
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
                breakdown(s, confirmed, coverage),
                warnings);
    }

    /**
     * The settlement sheet, line by line, in the order the manuals lay it out: the ceiling, then
     * what comes off it, then the result. Built here and not in the SPA so that the wording and
     * the arithmetic can't drift the day a deduction changes.
     */
    private List<SettlementResponse.Line> breakdown(CaseSettlement s, boolean confirmed,
                                                    Coverage coverage) {
        List<SettlementResponse.Line> lines = new ArrayList<>();

        boolean repair = s.getFormula() == SettlementFormula.REPAIR;
        boolean cappedByReplacement = !repair
                && s.getSettlementBasis() == SettlementBasis.LESSER_OF_SUM_AND_REPLACEMENT
                && s.getReplacementValue() != null
                && s.getReplacementValue().compareTo(s.getSumInsured()) < 0;

        if (repair) {
            // En una reparación el techo ES el presupuesto: encabezar con la suma asegurada haría
            // leer una cuenta que arranca en un número que no se va a pagar. La suma asegurada
            // aparece igual, como tope y como base de la franquicia.
            lines.add(SettlementResponse.Line.base("Presupuesto de reparación",
                    s.getReplacementValue() == null || s.getReplacementValue().signum() <= 0
                            ? "sin presupuesto acreditado — no hay monto que pagar"
                            : "acreditado en el expediente · tope: la suma asegurada, %s"
                                    .formatted(money(s.getSumInsured())),
                    repairCeiling(s)));
        } else {
            lines.add(SettlementResponse.Line.base("Suma asegurada", null, s.getSumInsured()));
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

        // Las deducciones que la cobertura tiene prendidas se muestran SIEMPRE, aunque den cero, y
        // con el motivo al lado. Omitirlas obligaba a explicar por separado —en un cartel de color,
        // lejos de la cuenta— por qué el total no las incluía; la hoja es donde el analista está
        // mirando la aritmética, y es donde eso se lee.
        // En reparación no se muestran: la póliza no se extingue, así que no hay premio anticipado
        // que cobrar y una línea en cero acá invitaría a preguntarse por qué no se descontó.
        if (!repair && coverage != null && coverage.isDeductPendingInstallments()) {
            lines.add(SettlementResponse.Line.deduction("Cuotas a vencer",
                    pendingInstallmentsDetail(s), s.getPendingInstallmentsAmount()));
        }

        if (coverage != null && coverage.isDeductOverdueBalance()) {
            lines.add(SettlementResponse.Line.deduction("Deuda vencida de la póliza",
                    s.getOverdueBalanceAmount().signum() > 0
                            ? "saldo impago del contrato (cláusula 102, art. 5)"
                            : "la póliza consultada no registra saldo impago",
                    s.getOverdueBalanceAmount()));
        }

        // Sobre una liquidación ya firmada donde el analista ajustó, la hoja no puede terminar en
        // el número que dio la fórmula: lo que se paga es el otro. Se muestran los dos, que es
        // exactamente lo que la fila guarda por separado.
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
     * Por qué las cuotas a vencer suman lo que suman. Los dos ceros posibles no significan lo
     * mismo y al analista le cambian la decisión: "no quedan cuotas" es un resultado, "no está el
     * dato" es una cuenta que no se pudo hacer y que él puede completar ajustando el monto.
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

    /** El presupuesto acotado por la suma asegurada, que es el techo real de una reparación. */
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
