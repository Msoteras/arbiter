package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.SettlementDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidSettlementException;
import ar.edu.utn.frba.arbiter.cases.exceptions.SettlementNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseSettlement;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicySnapshot;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseSettlementRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyCoverageRepository;
import ar.edu.utn.frba.arbiter.common.enums.SettlementBasis;
import ar.edu.utn.frba.arbiter.common.enums.SettlementStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
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
        // 800.000 − 80.000 de franquicia − 7 × 16.000 de cuotas a vencer.
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
        assertThat(response.breakdown().get(1).detail()).isEqualTo("10% de la suma asegurada");
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
     * Una deducción que la cobertura tiene prendida se muestra igual cuando da cero, y con el
     * motivo al lado. Antes esto era un cartel aparte: el analista leía la cuenta en un lado y por
     * qué no cerraba en otro, y los dos ceros posibles ("no quedan cuotas" y "no está el dato") se
     * veían iguales, cuando al segundo él lo puede completar ajustando el monto.
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
        // La línea está, en cero, y explica por qué — no en un aviso suelto lejos de la cuenta.
        assertThat(response.breakdown())
                .filteredOn(line -> "Cuotas a vencer".equals(line.concept()))
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.amount()).isEqualByComparingTo("0.00");
                    assertThat(line.detail()).contains("no trae el importe de cuota");
                });
        assertThat(response.warnings()).isEmpty();
    }

    /** El otro cero no es una falla: no quedaban cuotas por vencer, y así tiene que leerse. */
    @Test
    void tellsApartAnEmptyDeductionFromAMissingOne() {
        when(caseRepository.findPolicySnapshot(1L)).thenReturn(Optional.of(PolicySnapshot.builder()
                .id(99L)
                .externalPolicyNumber("POL-CEL-2026-042")
                .sumInsured(new BigDecimal("800000.00"))
                // Vigencia ya terminada a la fecha del hecho: no resta ninguna cuota.
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

    /** A signed amount doesn't move because someone reopened the screen with a different value. */
    @Test
    void anAlreadyConfirmedSettlementIsReturnedAsIsAndIgnoresThePreviewValue() {
        when(settlementRepository.findByCaseId(1L)).thenReturn(Optional.of(CaseSettlement.builder()
                .caseId(1L)
                .formula(SettlementCalculator.TOTAL_LOSS)
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

    // ─── Atribuciones (Anexo II) ────────────────────────────────────────────────

    /** Dentro del tope, la firma del analista alcanza: no hay segundo firmante que registrar. */
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

    /** El tope es inclusivo: un monto justo en el límite todavía no necesita al referente. */
    @Test
    void anAmountExactlyAtTheCeilingStillNeedsNoReferente() {
        claim.setClaimCause(claimCause(1L));
        when(authorityService.limitFor(1L)).thenReturn(new BigDecimal("608000.00"));

        CaseSettlement saved = settlementService.confirm(claim, 7L, "Documentación completa",
                new SettlementDecisionRequest(null, new BigDecimal("608000.00"), null));

        assertThat(saved.getStatus()).isEqualTo(SettlementStatus.AUTHORIZED);
    }

    /**
     * Por encima del tope queda esperando, y la justificación del analista se guarda en custodia:
     * la decisión todavía no se registró, y cuando el referente firme hay que reenviarla con lo
     * que él escribió, no con una nueva.
     */
    @Test
    void anAmountOverTheCeilingWaitsForTheReferenteAndHoldsTheJustification() {
        claim.setClaimCause(claimCause(1L));
        when(authorityService.limitFor(1L)).thenReturn(new BigDecimal("500000.00"));

        CaseSettlement saved = settlementService.confirm(claim, 7L, "Robo con denuncia y factura",
                new SettlementDecisionRequest(null, new BigDecimal("608000.00"), null));

        assertThat(saved.getStatus()).isEqualTo(SettlementStatus.PENDING_AUTHORIZATION);
        assertThat(saved.getAuthorityLimit()).isEqualByComparingTo("500000.00");
        assertThat(saved.getPendingJustification()).isEqualTo("Robo con denuncia y factura");
    }

    /** Un ramo sin tope configurado no frena nada: es como venía funcionando. */
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
     * Volver a confirmar pisa la fila que ya está. Sin heredar el id, el save intentaría insertar
     * una segunda liquidación para el mismo expediente y chocaría contra el UNIQUE.
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
        // El motivo de la devolución se limpia: volver a confirmar ES la respuesta a esa
        // devolución, y dejarlo la haría ver rechazada de nuevo.
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
        // A partir de acá la justificación vive en case_classification: dejarla también acá sería
        // la misma frase guardada dos veces.
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

    /** Firmar algo que no está esperando firma es actuar sobre una foto vieja de la pantalla. */
    @Test
    void authorizingSomethingThatIsNotWaitingIsRejected() {
        when(settlementRepository.findByCaseId(1L)).thenReturn(Optional.of(CaseSettlement.builder()
                .id(55L).caseId(1L).status(SettlementStatus.AUTHORIZED).build()));

        assertThatThrownBy(() -> settlementService.markAuthorized(1L, 3L))
                .isInstanceOf(InvalidSettlementException.class)
                .hasMessageContaining("no está esperando autorización");
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
