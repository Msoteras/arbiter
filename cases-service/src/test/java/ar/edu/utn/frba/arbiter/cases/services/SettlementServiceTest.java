package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.RepairOutcome;
import ar.edu.utn.frba.arbiter.cases.dto.DocumentAnalysisSummary;
import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementResponse;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementSuggestionTarget;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidSettlementException;
import ar.edu.utn.frba.arbiter.cases.exceptions.SettlementNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseSettlement;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
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
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.InsurerReferent;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettlementServiceTest {

    @Mock
    private CaseRepository caseRepository;

    @Mock
    private CaseSettlementRepository settlementRepository;

    @Mock
    private SettlementAuthorityService authorityService;

    @Mock
    private PolicyCoverageRepository policyCoverageRepository;

    @Mock
    private CaseDocumentAnalysisRepository documentAnalysisRepository;

    @Mock
    private ExpertAssessmentRepository expertAssessmentRepository;

    @Mock
    private InsurerReferentRepository insurerReferentRepository;

    /** Real, not mocked: the arithmetic under test is exactly the point of these cases. */
    @Spy
    private SettlementCalculator calculator = new SettlementCalculator();

    @InjectMocks
    private SettlementService settlementService;

    private Case claim;

    @BeforeEach
    void setUp() {
        claim = Case.builder()
                .id(1L)
                .occurredAt(LocalDateTime.of(2026, 6, 1, 10, 0))
                .policy(Policy.builder().id(1L).build())
                .coverage(coverage(SettlementBasis.SUM_INSURED, "10.00", true))
                .build();

        when(caseRepository.findById(1L)).thenReturn(Optional.of(claim));
        when(caseRepository.findPolicySnapshot(1L)).thenReturn(Optional.of(snapshot()));
        when(settlementRepository.findByCaseId(1L)).thenReturn(Optional.empty());
        when(settlementRepository.save(any(CaseSettlement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void confirmingTheProposedAmountRecordsItWithNoAdjustment() {
        // 800,000 − 80,000 franchise − 7 × 16,000 pending instalments.
        CaseSettlement saved = settlementService.confirm(claim, 7L, "Documentación completa",
                new SettlementDecisionRequest(null, new BigDecimal("608000.00"), null));

        assertThat(saved.getCalculatedAmount()).isEqualByComparingTo("608000.00");
        assertThat(saved.getSettledAmount()).isEqualByComparingTo("608000.00");
        assertThat(saved.getAdjustmentReason()).isNull();
        assertThat(saved.getAnalystId()).isEqualTo(7L);
        assertThat(saved.getConfirmedAt()).isNotNull();
    }

    /** Moving the number is allowed; doing it silently is not. */
    @Test
    void adjustingTheAmountWithoutAReasonIsRejected() {
        assertThatThrownBy(() -> settlementService.confirm(claim, 7L, "Documentación completa",
                new SettlementDecisionRequest(null, new BigDecimal("700000.00"), "   ")))
                .isInstanceOf(InvalidSettlementException.class)
                .hasMessageContaining("justificar");
    }

    @Test
    void anAdjustedAmountKeepsBothTheProposalAndTheReason() {
        CaseSettlement saved = settlementService.confirm(claim, 7L, "Documentación completa", new SettlementDecisionRequest(
                null, new BigDecimal("650000.00"), "  La factura acredita un equipo superior  "));

        assertThat(saved.getCalculatedAmount()).isEqualByComparingTo("608000.00");
        assertThat(saved.getSettledAmount()).isEqualByComparingTo("650000.00");
        assertThat(saved.getAdjustmentReason()).isEqualTo("La factura acredita un equipo superior");
    }

    /**
     * The sum insured is the contractual ceiling ("el límite máximo a indemnizar por cada
     * siniestro", art. 3): below it the analyst can move freely with a reason, above it there is
     * nothing to justify.
     */
    @Test
    void anAmountAboveTheSumInsuredIsRejectedEvenWithAReason() {
        assertThatThrownBy(() -> settlementService.confirm(claim, 7L, "Documentación completa", new SettlementDecisionRequest(
                null, new BigDecimal("900000.00"), "El asegurado insiste")))
                .isInstanceOf(InvalidSettlementException.class)
                .hasMessageContaining("suma asegurada");
    }

    @Test
    void approvingWithNoSettlementAtAllIsRejected() {
        assertThatThrownBy(() -> settlementService.confirm(claim, 7L, "Documentación completa", null))
                .isInstanceOf(InvalidSettlementException.class)
                .hasMessageContaining("determinar el monto");
    }

    @Test
    void theProposalExplainsEveryLineOfTheSheet() {
        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.confirmed()).isFalse();
        assertThat(response.settledAmount()).isNull();
        assertThat(response.calculatedAmount()).isEqualByComparingTo("608000.00");
        assertThat(response.breakdown())
                .extracting(SettlementResponse.Line::kind, SettlementResponse.Line::concept)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("BASE", "Suma asegurada"),
                        org.assertj.core.api.Assertions.tuple("DEDUCTION", "Franquicia"),
                        org.assertj.core.api.Assertions.tuple("DEDUCTION", "Cuotas a vencer"),
                        org.assertj.core.api.Assertions.tuple("TOTAL", "Monto a pagar"));
        // startsWith rather than isEqualTo: the currency formatter inserts a non-breaking space.
        assertThat(response.breakdown().get(1).detail()).startsWith("10% de la suma asegurada (");
    }

    /**
     * A coverage that settles by the lesser of sum insured and replacement cost, with nothing
     * accredited, quietly falls back to the sum insured. The analyst has to be told before they
     * sign, not after.
     */
    @Test
    void warnsWhenTheBasisNeedsAReplacementValueAndThereIsNone() {
        claim.setCoverage(coverage(SettlementBasis.LESSER_OF_SUM_AND_REPLACEMENT, "10.00", false));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.warnings()).anyMatch(w -> w.contains("valor de reposición acreditado"));
    }

    /**
     * A deduction the coverage enables is shown even at zero, with the reason next to it: the two
     * possible zeros ("no instalments left" and "data missing") must read differently, since the
     * analyst can make up for the second by adjusting the amount.
     */
    @Test
    void showsADeductionAtZeroOnTheSheetWithTheReasonInstead() {
        when(caseRepository.findPolicySnapshot(1L)).thenReturn(Optional.of(PolicySnapshot.builder()
                .id(99L)
                .externalPolicyNumber("POL-CEL-2026-042")
                .sumInsured(new BigDecimal("800000.00"))
                .effectiveTo(LocalDate.of(2027, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant())
                .eventsInYear(1)
                .queriedAt(Instant.now())
                .build()));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.calculatedAmount()).isEqualByComparingTo("720000.00");
        assertThat(response.breakdown())
                .filteredOn(line -> "Cuotas a vencer".equals(line.concept()))
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.amount()).isEqualByComparingTo("0.00");
                    assertThat(line.detail()).contains("no trae el importe de cuota");
                });
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void tellsApartAnEmptyDeductionFromAMissingOne() {
        when(caseRepository.findPolicySnapshot(1L)).thenReturn(Optional.of(PolicySnapshot.builder()
                .id(99L)
                .externalPolicyNumber("POL-CEL-2026-042")
                .sumInsured(new BigDecimal("800000.00"))
                // Term already over at the event date: no instalments left.
                .effectiveTo(LocalDate.of(2026, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant())
                .installmentAmount(new BigDecimal("16000.00"))
                .eventsInYear(1)
                .queriedAt(Instant.now())
                .build()));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.breakdown())
                .filteredOn(line -> "Cuotas a vencer".equals(line.concept()))
                .singleElement()
                .satisfies(line -> assertThat(line.detail()).isEqualTo("no quedan cuotas por vencer"));
    }

    @Test
    void anAlreadyConfirmedSettlementIsReturnedAsIsAndIgnoresThePreviewValue() {
        when(settlementRepository.findByCaseId(1L)).thenReturn(Optional.of(CaseSettlement.builder()
                .caseId(1L)
                .formula(SettlementFormula.TOTAL_LOSS)
                .sumInsured(new BigDecimal("800000.00"))
                .settlementBasis(SettlementBasis.SUM_INSURED)
                .deductibleRate(new BigDecimal("10.00"))
                .deductibleAmount(new BigDecimal("80000.00"))
                .calculatedAmount(new BigDecimal("720000.00"))
                .settledAmount(new BigDecimal("700000.00"))
                .adjustmentReason("Ajuste acordado con el asegurado")
                .coverageId(42L)
                .analystId(7L)
                .calculatedAt(Instant.now())
                .confirmedAt(Instant.now())
                .build()));

        SettlementResponse response = settlementService.forCase(1L, new BigDecimal("10.00"));

        assertThat(response.confirmed()).isTrue();
        assertThat(response.settledAmount()).isEqualByComparingTo("700000.00");
        assertThat(response.calculatedAmount()).isEqualByComparingTo("720000.00");
        assertThat(response.warnings()).isEmpty();
    }

    /**
     * In a repair the amount comes from the quote, which the model already read while classifying.
     * It's offered with its source: a number without provenance is worth less than none.
     */
    @Test
    void suggestsTheAmountTheModelReadOffTheRepairQuote() {
        claim.setCoverage(repairCoverage());
        when(documentAnalysisRepository.findByCaseId(1L)).thenReturn(List.of(
                document("police_report", null),
                document("repair_quote", new BigDecimal("95000.00"))));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.suggestedAmount()).isEqualByComparingTo("95000.00");
        assertThat(response.suggestedFrom()).isEqualTo("repair_quote");
        // Suggested, not applied: the calculation stays at zero until the analyst takes it.
        assertThat(response.calculatedAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void suggestsThePurchaseProofWhenTheCeilingIsTheLesserOfTheTwo() {
        claim.setCoverage(coverage(SettlementBasis.LESSER_OF_SUM_AND_REPLACEMENT, "10.00", false));
        when(documentAnalysisRepository.findByCaseId(1L)).thenReturn(List.of(
                document("purchase_proof", new BigDecimal("620000.00")),
                document("repair_quote", new BigDecimal("95000.00"))));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.suggestedAmount()).isEqualByComparingTo("620000.00");
        assertThat(response.suggestedFrom()).isEqualTo("purchase_proof");
        assertThat(response.suggestedFor()).isEqualTo(SettlementSuggestionTarget.ACCREDITED_AMOUNT);
    }

    /**
     * Where the field changes nothing (total loss by sum insured) nothing is suggested: it would
     * invite entering data that doesn't change the amount.
     */
    @Test
    void suggestsNothingWhereTheAccreditedAmountChangesNothing() {
        when(documentAnalysisRepository.findByCaseId(1L)).thenReturn(List.of(
                document("purchase_proof", new BigDecimal("620000.00"))));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.suggestedAmount()).isNull();
        assertThat(response.suggestedFrom()).isNull();
    }

    @Test
    void suggestsNothingWhenTheRightDocumentHasNoReadableAmount() {
        claim.setCoverage(repairCoverage());
        when(documentAnalysisRepository.findByCaseId(1L)).thenReturn(List.of(
                document("repair_quote", null),
                document("purchase_proof", new BigDecimal("620000.00"))));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.suggestedAmount()).isNull();
    }

    @Test
    void suggestsNothingOnAConfirmedSettlement() {
        when(settlementRepository.findByCaseId(1L)).thenReturn(Optional.of(CaseSettlement.builder()
                .caseId(1L).sumInsured(new BigDecimal("800000.00"))
                .settlementBasis(SettlementBasis.SUM_INSURED)
                .calculatedAmount(new BigDecimal("720000.00"))
                .settledAmount(new BigDecimal("720000.00"))
                .status(SettlementStatus.AUTHORIZED)
                .calculatedAt(Instant.now()).confirmedAt(Instant.now())
                .build()));

        assertThat(settlementService.forCase(1L, null).suggestedAmount()).isNull();
    }

    /**
     * After an expert assessment the suggested amount is the expert's, not the quote's: someone
     * inspected the item, versus a paper the insured brought. Offering the latter would suggest the
     * weaker source.
     */
    @Test
    void theExpertAmountWinsOverTheQuote() {
        claim.setCoverage(repairCoverage());
        when(documentAnalysisRepository.findByCaseId(1L)).thenReturn(List.of(
                document("repair_quote", new BigDecimal("95000.00"))));
        when(expertAssessmentRepository.findByCaseIdOrderByDerivedAtDesc(1L)).thenReturn(List.of(
                ExpertAssessment.builder().caseId(1L).reportReceivedAt(Instant.now())
                        .indemnifiableAmount(new BigDecimal("120000.00")).build()));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.suggestedAmount()).isEqualByComparingTo("120000.00");
        assertThat(response.suggestedFrom()).isEqualTo("expert_report");
        assertThat(response.suggestedFor()).isEqualTo(SettlementSuggestionTarget.ACCREDITED_AMOUNT);
    }

    /**
     * The repair shop's quote also beats the insured's paper, for the same reason. It lives in its
     * own column (what the repair COSTS, not what the claim is worth), which in a repair is exactly
     * the calculation base.
     */
    @Test
    void theRepairShopQuoteIsSuggestedAsTheAccreditedAmount() {
        claim.setCoverage(repairCoverage());
        when(documentAnalysisRepository.findByCaseId(1L)).thenReturn(List.of(
                document("repair_quote", new BigDecimal("95000.00"))));
        when(expertAssessmentRepository.findByCaseIdOrderByDerivedAtDesc(1L)).thenReturn(List.of(
                ExpertAssessment.builder().caseId(1L).providerType(ProviderType.SERVICIO_TECNICO)
                        .reportReceivedAt(Instant.now())
                        .repairCost(new BigDecimal("180000.00")).build()));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.suggestedAmount()).isEqualByComparingTo("180000.00");
        assertThat(response.suggestedFrom()).isEqualTo("repair_report");
        assertThat(response.suggestedFor()).isEqualTo(SettlementSuggestionTarget.ACCREDITED_AMOUNT);
    }

    /**
     * With two valuations on the same case the latest received wins, as the insurer handles them.
     * Here the repair shop answered after the expert.
     */
    @Test
    void theLatestValuationReplacesTheEarlierOne() {
        claim.setCoverage(repairCoverage());
        Instant yesterday = Instant.now().minusSeconds(86_400);
        when(expertAssessmentRepository.findByCaseIdOrderByDerivedAtDesc(1L)).thenReturn(List.of(
                ExpertAssessment.builder().caseId(1L).providerType(ProviderType.ESTUDIO_LIQUIDADOR)
                        .reportReceivedAt(yesterday)
                        .indemnifiableAmount(new BigDecimal("120000.00")).build(),
                ExpertAssessment.builder().caseId(1L).providerType(ProviderType.SERVICIO_TECNICO)
                        .reportReceivedAt(Instant.now())
                        .repairCost(new BigDecimal("180000.00")).build()));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.suggestedAmount()).isEqualByComparingTo("180000.00");
        assertThat(response.suggestedFrom()).isEqualTo("repair_report");
    }

    /**
     * By sum insured only the final amount can be proposed, and only the expert speaks to that: what
     * the shop charges for a repair isn't an opinion on what should be paid.
     */
    @Test
    void theRepairShopQuoteIsNotOfferedAsTheAmountToPay() {
        when(expertAssessmentRepository.findByCaseIdOrderByDerivedAtDesc(1L)).thenReturn(List.of(
                ExpertAssessment.builder().caseId(1L).providerType(ProviderType.SERVICIO_TECNICO)
                        .reportReceivedAt(Instant.now())
                        .repairCost(new BigDecimal("180000.00")).build()));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.suggestedAmount()).isNull();
        assertThat(response.suggestedFor()).isNull();
    }

    /**
     * The expert determines what should be paid, not a replacement value, so it still applies when
     * settling by sum insured (where there's no accredited amount) and targets the final amount.
     * Those expensive coverages are the only ones that reach an expert assessment.
     */
    @Test
    void theExpertAmountIsSuggestedForTheAmountItselfWhenSettlingBySumInsured() {
        when(expertAssessmentRepository.findByCaseIdOrderByDerivedAtDesc(1L)).thenReturn(List.of(
                ExpertAssessment.builder().caseId(1L).reportReceivedAt(Instant.now())
                        .indemnifiableAmount(new BigDecimal("612500.00")).build()));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.suggestedAmount()).isEqualByComparingTo("612500.00");
        assertThat(response.suggestedFrom()).isEqualTo("expert_report");
        assertThat(response.suggestedFor()).isEqualTo(SettlementSuggestionTarget.SETTLED_AMOUNT);
    }

    /**
     * The purchase proof doesn't sneak in that way: by sum insured it doesn't change the amount.
     * Only the expert assessment has a say there.
     */
    @Test
    void aDocumentAmountIsStillNotSuggestedWhenSettlingBySumInsured() {
        when(documentAnalysisRepository.findByCaseId(1L)).thenReturn(List.of(
                document("purchase_proof", new BigDecimal("620000.00"))));
        when(expertAssessmentRepository.findByCaseIdOrderByDerivedAtDesc(1L)).thenReturn(List.of(
                ExpertAssessment.builder().caseId(1L).reportReceivedAt(Instant.now()).indemnifiableAmount(null).build()));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.suggestedAmount()).isNull();
        assertThat(response.suggestedFor()).isNull();
    }

    /**
     * An expert report with no amount doesn't hide the quote: not every report has a number (a
     * confirmed fraud has nothing to indemnify), and then the quote is still the best there is.
     */
    @Test
    void anExpertReportWithNoAmountFallsBackToTheQuote() {
        claim.setCoverage(repairCoverage());
        when(documentAnalysisRepository.findByCaseId(1L)).thenReturn(List.of(
                document("repair_quote", new BigDecimal("95000.00"))));
        when(expertAssessmentRepository.findByCaseIdOrderByDerivedAtDesc(1L)).thenReturn(List.of(
                ExpertAssessment.builder().caseId(1L).reportReceivedAt(Instant.now()).indemnifiableAmount(null).build()));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.suggestedAmount()).isEqualByComparingTo("95000.00");
        assertThat(response.suggestedFrom()).isEqualTo("repair_quote");
    }

    private DocumentAnalysisSummary document(String type, BigDecimal amount) {
        return new DocumentAnalysisSummary(type, "…", null, amount, null, null, null, null, "TITULAR",
                List.of(), List.of(), "COMPLETE");
    }

    private Coverage repairCoverage() {
        return Coverage.builder()
                .id(43L)
                .name("Daño accidental")
                .settlementFormula(SettlementFormula.REPAIR)
                .settlementBasis(SettlementBasis.SUM_INSURED)
                .deductible(new BigDecimal("10.00"))
                .build();
    }

    /**
     * A damage coverage settles by repair assuming the item survived. If the shop declares it
     * irreparable, for insurance purposes it's gone as if stolen, and the sum insured is paid.
     */
    @Test
    void anIrreparableItemIsSettledAsATotalLoss() {
        claim.setCoverage(repairCoverage());
        when(expertAssessmentRepository.findByCaseIdOrderByDerivedAtDesc(1L)).thenReturn(List.of(
                ExpertAssessment.builder().caseId(1L).providerType(ProviderType.SERVICIO_TECNICO)
                        .reportReceivedAt(Instant.now())
                        .repairOutcome(RepairOutcome.IRREPARABLE).build()));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.formula()).isEqualTo(SettlementFormula.TOTAL_LOSS);
        assertThat(response.calculatedAmount()).isEqualByComparingTo("720000.00");
    }

    /** Switching formulas silently would change the analyst's sheet without saying why. */
    @Test
    void theSheetSaysWhyItStoppedBeingARepair() {
        claim.setCoverage(repairCoverage());
        when(expertAssessmentRepository.findByCaseIdOrderByDerivedAtDesc(1L)).thenReturn(List.of(
                ExpertAssessment.builder().caseId(1L).providerType(ProviderType.SERVICIO_TECNICO)
                        .reportReceivedAt(Instant.now())
                        .repairOutcome(RepairOutcome.IRREPARABLE).build()));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.breakdown())
                .anyMatch(line -> "Suma asegurada".equals(line.concept())
                        && line.detail() != null && line.detail().contains("irreparable"));
        // And this coverage doesn't deduct instalments, as a theft would: the switch was configured
        // for repairs, where the deduction doesn't exist.
        assertThat(response.breakdown())
                .anyMatch(line -> "Cuotas a vencer".equals(line.concept())
                        && line.detail() != null && line.detail().contains("no tiene configurado"));
    }

    /**
     * The warning looks at the applied formula, not the coverage's: asking for a repair quote next
     * to a sheet settled as a total loss would contradict it.
     */
    @Test
    void anIrreparableItemIsNotAskedForARepairQuote() {
        claim.setCoverage(repairCoverage());
        when(expertAssessmentRepository.findByCaseIdOrderByDerivedAtDesc(1L)).thenReturn(List.of(
                ExpertAssessment.builder().caseId(1L).providerType(ProviderType.SERVICIO_TECNICO)
                        .reportReceivedAt(Instant.now())
                        .repairOutcome(RepairOutcome.IRREPARABLE).build()));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.warnings()).noneMatch(w -> w.contains("presupuesto"));
    }

    /** Repaired or quoted, the coverage rules: it's still a repair. */
    @Test
    void aRepairedItemStillSettlesAsARepair() {
        claim.setCoverage(repairCoverage());
        when(expertAssessmentRepository.findByCaseIdOrderByDerivedAtDesc(1L)).thenReturn(List.of(
                ExpertAssessment.builder().caseId(1L).providerType(ProviderType.SERVICIO_TECNICO)
                        .reportReceivedAt(Instant.now())
                        .repairOutcome(RepairOutcome.QUOTE_SENT)
                        .repairCost(new BigDecimal("180000.00")).build()));

        SettlementResponse response = settlementService.forCase(1L, null);

        assertThat(response.formula()).isEqualTo(SettlementFormula.REPAIR);
    }

    @Test
    void anAmountWithinTheBranchAttributionIsAuthorizedOnTheSpot() {
        claim.setClaimCause(claimCause(1L));
        when(authorityService.limitFor(1L)).thenReturn(new BigDecimal("700000.00"));

        CaseSettlement saved = settlementService.confirm(claim, 7L, "Documentación completa",
                new SettlementDecisionRequest(null, new BigDecimal("608000.00"), null));

        assertThat(saved.getStatus()).isEqualTo(SettlementStatus.AUTHORIZED);
        assertThat(saved.getAuthorityLimit()).isEqualByComparingTo("700000.00");
        assertThat(saved.getPendingJustification()).isNull();
        assertThat(saved.getAuthorizedByUserId()).isNull();
    }

    @Test
    void anAmountExactlyAtTheCeilingStillNeedsNoReferent() {
        claim.setClaimCause(claimCause(1L));
        when(authorityService.limitFor(1L)).thenReturn(new BigDecimal("608000.00"));

        CaseSettlement saved = settlementService.confirm(claim, 7L, "Documentación completa",
                new SettlementDecisionRequest(null, new BigDecimal("608000.00"), null));

        assertThat(saved.getStatus()).isEqualTo(SettlementStatus.AUTHORIZED);
    }

    /**
     * Above the ceiling it waits, and the analyst's justification is held: the decision isn't
     * recorded yet, and when the referent signs it must be forwarded with what the analyst wrote.
     */
    @Test
    void anAmountOverTheCeilingWaitsForTheReferentAndHoldsTheJustification() {
        claim.setClaimCause(claimCause(1L));
        when(authorityService.limitFor(1L)).thenReturn(new BigDecimal("500000.00"));

        CaseSettlement saved = settlementService.confirm(claim, 7L, "Robo con denuncia y factura",
                new SettlementDecisionRequest(null, new BigDecimal("608000.00"), null));

        assertThat(saved.getStatus()).isEqualTo(SettlementStatus.PENDING_AUTHORIZATION);
        assertThat(saved.getAuthorityLimit()).isEqualByComparingTo("500000.00");
        assertThat(saved.getPendingJustification()).isEqualTo("Robo con denuncia y factura");
    }

    @Test
    void aBranchWithNoCeilingAuthorizesAnyAmount() {
        claim.setClaimCause(claimCause(1L));
        when(authorityService.limitFor(1L)).thenReturn(null);

        CaseSettlement saved = settlementService.confirm(claim, 7L, "Documentación completa",
                new SettlementDecisionRequest(null, new BigDecimal("608000.00"), null));

        assertThat(saved.getStatus()).isEqualTo(SettlementStatus.AUTHORIZED);
        assertThat(saved.getAuthorityLimit()).isNull();
    }

    /**
     * Without inheriting the id, the save would insert a second settlement for the same case and hit
     * the UNIQUE constraint.
     */
    @Test
    void reconfirmingOverwritesTheExistingRowInsteadOfInsertingASecond() {
        CaseSettlement existing = CaseSettlement.builder()
                .id(55L)
                .caseId(1L)
                .status(SettlementStatus.RETURNED)
                .returnReason("El presupuesto no respalda ese monto")
                .build();
        when(settlementRepository.findByCaseId(1L)).thenReturn(Optional.of(existing));

        CaseSettlement saved = settlementService.confirm(claim, 7L, "Corregido con el presupuesto",
                new SettlementDecisionRequest(null, new BigDecimal("608000.00"), null));

        assertThat(saved.getId()).isEqualTo(55L);
        // The return reason is cleared: reconfirming IS the answer to it, and keeping it would make
        // the settlement look returned again.
        assertThat(saved.getReturnReason()).isNull();
    }

    @Test
    void authorizingRecordsWhoSignedAndReleasesTheHeldJustification() {
        when(settlementRepository.findByCaseId(1L)).thenReturn(Optional.of(CaseSettlement.builder()
                .id(55L).caseId(1L)
                .status(SettlementStatus.PENDING_AUTHORIZATION)
                .pendingJustification("Robo con denuncia y factura")
                .build()));

        CaseSettlement saved = settlementService.markAuthorized(1L, 3L);

        assertThat(saved.getStatus()).isEqualTo(SettlementStatus.AUTHORIZED);
        assertThat(saved.getAuthorizedByUserId()).isEqualTo(3L);
        assertThat(saved.getAuthorizedAt()).isNotNull();
        // From here on the justification lives in case_classification; keeping it here too would
        // store it twice.
        assertThat(saved.getPendingJustification()).isNull();
    }

    @Test
    void returningRequiresAReasonAndDoesNotTouchTheAmount() {
        when(settlementRepository.findByCaseId(1L)).thenReturn(Optional.of(CaseSettlement.builder()
                .id(55L).caseId(1L)
                .settledAmount(new BigDecimal("608000.00"))
                .status(SettlementStatus.PENDING_AUTHORIZATION)
                .build()));

        assertThatThrownBy(() -> settlementService.returnToAnalyst(1L, 3L, "  "))
                .isInstanceOf(InvalidSettlementException.class)
                .hasMessageContaining("motivo");

        CaseSettlement saved = settlementService.returnToAnalyst(1L, 3L, "  Falta el presupuesto  ");

        assertThat(saved.getStatus()).isEqualTo(SettlementStatus.RETURNED);
        assertThat(saved.getReturnReason()).isEqualTo("Falta el presupuesto");
        assertThat(saved.getSettledAmount()).isEqualByComparingTo("608000.00");
    }

    /** Signing something that isn't waiting for a signature means acting on a stale screen. */
    @Test
    void authorizingSomethingThatIsNotWaitingIsRejected() {
        when(settlementRepository.findByCaseId(1L)).thenReturn(Optional.of(CaseSettlement.builder()
                .id(55L).caseId(1L).status(SettlementStatus.AUTHORIZED).build()));

        assertThatThrownBy(() -> settlementService.markAuthorized(1L, 3L))
                .isInstanceOf(InvalidSettlementException.class)
                .hasMessageContaining("no está esperando autorización");
    }

    /** An authorized one carries who and when, with the excess already subtracted as in the inbox. */
    @Test
    void authorizedListCarriesWhoAndWhenWithTheCaseData() {
        Instant authorizedAt = Instant.parse("2026-09-20T15:00:00Z");
        when(settlementRepository.findTop50ByStatusAndAuthorizedAtIsNotNullOrderByAuthorizedAtDesc(
                SettlementStatus.AUTHORIZED)).thenReturn(List.of(CaseSettlement.builder()
                .id(55L).caseId(1L)
                .calculatedAmount(new BigDecimal("990000.00"))
                .settledAmount(new BigDecimal("990000.00"))
                .authorityLimit(new BigDecimal("500000.00"))
                .status(SettlementStatus.AUTHORIZED)
                .authorizedByUserId(3L)
                .authorizedAt(authorizedAt)
                .build()));
        when(caseRepository.findAllById(List.of(1L))).thenReturn(List.of(claim));
        when(insurerReferentRepository.findByUser_IdIn(List.of(3L))).thenReturn(List.of(
                InsurerReferent.builder().name("Sofía").surname("Martínez")
                        .user(User.builder().id(3L).build()).build()));

        var rows = settlementService.authorizedByReferent();

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.caseId()).isEqualTo(1L);
            assertThat(row.authorizedAt()).isEqualTo(authorizedAt);
            assertThat(row.authorizedByName()).isEqualTo("Sofía Martínez");
            assertThat(row.excess()).isEqualByComparingTo("490000.00");
        });
    }

    /** A case that can no longer be read doesn't break the list: it's skipped, as in the pending list. */
    @Test
    void authorizedListSkipsSettlementsWhoseCaseIsGone() {
        when(settlementRepository.findTop50ByStatusAndAuthorizedAtIsNotNullOrderByAuthorizedAtDesc(
                SettlementStatus.AUTHORIZED)).thenReturn(List.of(CaseSettlement.builder()
                .id(56L).caseId(99L)
                .settledAmount(new BigDecimal("700000.00"))
                .status(SettlementStatus.AUTHORIZED)
                .authorizedAt(Instant.now())
                .build()));
        when(caseRepository.findAllById(List.of(99L))).thenReturn(List.of());

        assertThat(settlementService.authorizedByReferent()).isEmpty();
    }

    @Test
    void actingOnACaseWithNoSettlementIsA404() {
        when(settlementRepository.findByCaseId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.markAuthorized(1L, 3L))
                .isInstanceOf(SettlementNotFoundException.class);
    }

    private ClaimCause claimCause(Long branchId) {
        return ClaimCause.builder()
                .id(9L)
                .name("Robo en vía pública")
                .branch(Branch.builder().id(branchId).name("Celulares").build())
                .build();
    }

    private Coverage coverage(SettlementBasis basis, String deductible, boolean deductInstallments) {
        return Coverage.builder()
                .id(42L)
                .name("Robo de celular")
                .settlementBasis(basis)
                .deductible(new BigDecimal(deductible))
                .deductPendingInstallments(deductInstallments)
                .build();
    }

    private PolicySnapshot snapshot() {
        return PolicySnapshot.builder()
                .id(99L)
                .externalPolicyNumber("POL-CEL-2026-042")
                .sumInsured(new BigDecimal("800000.00"))
                .effectiveTo(LocalDate.of(2027, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant())
                .installmentAmount(new BigDecimal("16000.00"))
                .eventsInYear(1)
                .queriedAt(Instant.now())
                .build();
    }
}
