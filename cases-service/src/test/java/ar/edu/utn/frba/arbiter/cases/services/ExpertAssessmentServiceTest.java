package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.DerivationOptionsResponse;
import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.dto.RepairOutcome;
import ar.edu.utn.frba.arbiter.cases.dto.DeriveToExpertRequest;
import ar.edu.utn.frba.arbiter.cases.dto.ExpertAssessmentResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidRepairReportException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseAssignedToAnotherAnalystException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotAssignedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DerivationNotAllowedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.ExpertAssessmentNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.ExpertFirmNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.ExpertReportAlreadyReceivedException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertFirm;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ExpertAssessmentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ExpertFirmRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpertAssessmentServiceTest {

    private static final Long CASE_ID = 7L;
    private static final Long BRANCH_ID = 1L;
    private static final BigDecimal CLAIMED_AMOUNT = new BigDecimal("950000");
    private static final String ANALYST_EMAIL = "analista.arbiter@gmail.com";
    private static final Long CLAIM_CAUSE_ID = 4L;

    @Mock
    private CaseRepository caseRepository;

    @Mock
    private CaseDocumentRepository caseDocumentRepository;

    @Mock
    private ExpertAssessmentRepository expertAssessmentRepository;

    @Mock
    private ExpertFirmRepository expertFirmRepository;

    @Mock
    private ClaimsAnalystRepository claimsAnalystRepository;

    /** The state machine guard is tested separately, in CaseStatusServiceTest. */
    @Mock
    private CaseStatusService caseStatusService;

    @Mock
    private ExpertNotificationService expertNotificationService;

    @Mock
    private RulesServiceClient rulesServiceClient;

    @Mock
    private FraudRecordService fraudRecordService;

    @InjectMocks
    private ExpertAssessmentService expertAssessmentService;

    @BeforeEach
    void authenticateAnalyst() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ANALYST_EMAIL, "n/a", List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void derive_copiesTheFirm_movesTheCase_andEmailsTheExpert() {
        Case caseRecord = caseAwaitingReview();
        ExpertFirm firm = firm(3L, "Estudio Verifica S.R.L.", "verifica@example.com");
        givenCaseAndAnalyst(caseRecord);
        when(expertFirmRepository.findAvailableForBranch(BRANCH_ID, ProviderType.ESTUDIO_LIQUIDADOR)).thenReturn(List.of(firm));
        when(expertAssessmentRepository.save(any(ExpertAssessment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(expertNotificationService.notifyDerivation(eq(caseRecord), any(ExpertAssessment.class)))
                .thenReturn(Instant.parse("2026-08-17T12:00:00Z"));

        ExpertAssessmentResponse response = expertAssessmentService.derive(CASE_ID,
                new DeriveToExpertRequest(3L, "Banda CRÍTICA e imagen reutilizada"), ProviderType.ESTUDIO_LIQUIDADOR);

        // Copied, not read through the association: editing the catalog later must not rewrite
        // who assessed THIS claim.
        assertThat(response.expertName()).isEqualTo("Estudio Verifica S.R.L.");
        assertThat(response.expertEmail()).isEqualTo("verifica@example.com");
        assertThat(response.reason()).isEqualTo("Banda CRÍTICA e imagen reutilizada");
        assertThat(response.notified()).isTrue();
        assertThat(response.verdict()).isNull();

        verify(caseStatusService).transition(eq(caseRecord), eq(CaseStatus.PENDING_EXPERT_REPORT),
                eq(StatusChangeActor.ANALYST), any());
    }

    /**
     * The mail is best-effort: a SendGrid failure can't undo a derivation that happened. But it must
     * show, or a case waiting on an expert nobody told would be invisible.
     */
    @Test
    void derive_recordsThatNobodyWasNotified_whenTheEmailNeverWentOut() {
        Case caseRecord = caseAwaitingReview();
        givenCaseAndAnalyst(caseRecord);
        when(expertFirmRepository.findAvailableForBranch(BRANCH_ID, ProviderType.ESTUDIO_LIQUIDADOR))
                .thenReturn(List.of(firm(3L, "Estudio Verifica S.R.L.", "verifica@example.com")));
        when(expertAssessmentRepository.save(any(ExpertAssessment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(expertNotificationService.notifyDerivation(any(), any())).thenReturn(null);

        ExpertAssessmentResponse response = expertAssessmentService.derive(CASE_ID,
                new DeriveToExpertRequest(3L, "Sospecha de preexistencia del daño"), ProviderType.ESTUDIO_LIQUIDADOR);

        assertThat(response.notified()).isFalse();
        verify(caseStatusService).transition(any(), eq(CaseStatus.PENDING_EXPERT_REPORT), any(), any());
    }

    @Test
    void derive_rejectsAFirmThatIsNotAvailableForTheCase() {
        Case caseRecord = caseAwaitingReview();
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        when(claimsAnalystRepository.findByEmail(ANALYST_EMAIL)).thenReturn(Optional.of(analyst()));
        givenPolicy(new BigDecimal("500000"));
        when(expertFirmRepository.findAvailableForBranch(BRANCH_ID, ProviderType.ESTUDIO_LIQUIDADOR))
                .thenReturn(List.of(firm(3L, "Estudio Verifica S.R.L.", "verifica@example.com")));

        assertThatThrownBy(() -> expertAssessmentService.derive(CASE_ID,
                new DeriveToExpertRequest(99L, "motivo"), ProviderType.ESTUDIO_LIQUIDADOR))
                .isInstanceOf(ExpertFirmNotFoundException.class);

        verify(expertAssessmentRepository, never()).save(any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void receiveReport_storesTheReport_recordsTheVerdict_andHandsTheCaseBack() {
        Case caseRecord = caseInStatus(CaseStatus.PENDING_EXPERT_REPORT);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        when(expertAssessmentRepository.findByCaseIdAndProviderType(CASE_ID, ProviderType.ESTUDIO_LIQUIDADOR)).thenReturn(Optional.of(awaitingAssessment()));
        when(caseDocumentRepository.findByCaseIdAndType(CASE_ID, "expert_report"))
                .thenReturn(Optional.empty());
        when(caseDocumentRepository.save(any(CaseDocument.class))).thenAnswer(invocation -> {
            CaseDocument saved = invocation.getArgument(0);
            saved.setId(42L);
            return saved;
        });
        when(expertAssessmentRepository.save(any(ExpertAssessment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExpertAssessmentResponse response = expertAssessmentService.receiveReport(CASE_ID,
                ExpertVerdict.FRAUD_CONFIRMED, "El equipo ya estaba dañado antes de la vigencia", null, new MockMultipartFile("report", "informe.pdf", "application/pdf", "PDF".getBytes()));

        assertThat(response.verdict()).isEqualTo(ExpertVerdict.FRAUD_CONFIRMED);
        assertThat(response.reportReceivedAt()).isNotNull();
        assertThat(response.reportDocumentId()).isEqualTo(42L);

        ArgumentCaptor<CaseDocument> document = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).save(document.capture());
        assertThat(document.getValue().getType()).isEqualTo("expert_report");
        assertThat(document.getValue().getFilename()).isEqualTo("informe.pdf");

        verify(caseStatusService).transition(eq(caseRecord), eq(CaseStatus.PENDING_ANALYST_REVIEW),
                eq(StatusChangeActor.ANALYST), any());
    }

    /**
     * The expert already proved it: an extra manual step to reach the person's record would be
     * forgotten, leaving someone unflagged with a report that says otherwise.
     */
    @Test
    void receiveReport_confirmingFraud_recordsItOnTheInsured() {
        givenAReportCanBeFiled();

        expertAssessmentService.receiveReport(CASE_ID, ExpertVerdict.FRAUD_CONFIRMED,
                "El equipo ya estaba dañado antes de la vigencia", null, new MockMultipartFile("report", "informe.pdf", "application/pdf", "PDF".getBytes()));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(fraudRecordService).registerFromExpertReport(eq(CASE_ID), reason.capture());
        // The reason must say who found it and what they wrote: it's what gets read years later
        // next to the flag on the person.
        assertThat(reason.getValue())
                .contains("Estudio Verifica S.R.L.")
                .contains("El equipo ya estaba dañado antes de la vigencia");
    }

    @Test
    void receiveReport_withoutConfirmedFraud_recordsNothingOnTheInsured() {
        givenAReportCanBeFiled();

        expertAssessmentService.receiveReport(CASE_ID, ExpertVerdict.FRAUD_DISCARDED, "Todo en regla", null, new MockMultipartFile("report", "informe.pdf", "application/pdf", "PDF".getBytes()));

        verify(fraudRecordService, never()).registerFromExpertReport(any(), any());
    }

    private void givenAReportCanBeFiled() {
        when(caseRepository.findById(CASE_ID))
                .thenReturn(Optional.of(caseInStatus(CaseStatus.PENDING_EXPERT_REPORT)));
        when(expertAssessmentRepository.findByCaseIdAndProviderType(CASE_ID, ProviderType.ESTUDIO_LIQUIDADOR)).thenReturn(Optional.of(awaitingAssessment()));
        when(caseDocumentRepository.findByCaseIdAndType(CASE_ID, "expert_report"))
                .thenReturn(Optional.empty());
        when(caseDocumentRepository.save(any(CaseDocument.class))).thenAnswer(invocation -> {
            CaseDocument saved = invocation.getArgument(0);
            saved.setId(42L);
            return saved;
        });
        when(expertAssessmentRepository.save(any(ExpertAssessment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * The report is evidence and is never overwritten. The case status can't detect it: a case back
     * in review with its report is in the same status as one never derived.
     */
    @Test
    void receiveReport_rejectsASecondReport() {
        Case caseRecord = caseInStatus(CaseStatus.PENDING_ANALYST_REVIEW);
        ExpertAssessment alreadyReturned = awaitingAssessment();
        alreadyReturned.setReportReceivedAt(Instant.parse("2026-08-16T10:00:00Z"));
        alreadyReturned.setVerdict(ExpertVerdict.FRAUD_DISCARDED);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        when(expertAssessmentRepository.findByCaseIdAndProviderType(CASE_ID, ProviderType.ESTUDIO_LIQUIDADOR)).thenReturn(Optional.of(alreadyReturned));

        assertThatThrownBy(() -> expertAssessmentService.receiveReport(CASE_ID,
                ExpertVerdict.FRAUD_CONFIRMED, "otra cosa", null, new MockMultipartFile("report", "otro.pdf", "application/pdf", "PDF".getBytes())))
                .isInstanceOf(ExpertReportAlreadyReceivedException.class);

        verify(caseDocumentRepository, never()).save(any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void receiveReport_failsWhenTheCaseWasNeverDerived() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseAwaitingReview()));
        when(expertAssessmentRepository.findByCaseIdAndProviderType(CASE_ID, ProviderType.ESTUDIO_LIQUIDADOR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> expertAssessmentService.receiveReport(CASE_ID,
                ExpertVerdict.INCONCLUSIVE, null, null, new MockMultipartFile("report", "informe.pdf", "application/pdf", "PDF".getBytes())))
                .isInstanceOf(ExpertAssessmentNotFoundException.class);
    }

    @Test
    void options_offersTheFirmsOfTheBranch_whenTheAmountClearsTheThreshold() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseAwaitingReview()));
        givenPolicy(new BigDecimal("500000"));
        when(expertFirmRepository.findAvailableForBranch(BRANCH_ID, ProviderType.ESTUDIO_LIQUIDADOR))
                .thenReturn(List.of(firm(3L, "Estudio Verifica S.R.L.", "verifica@example.com")));

        DerivationOptionsResponse options = expertAssessmentService.options(CASE_ID, ProviderType.ESTUDIO_LIQUIDADOR);

        assertThat(options.eligible()).isTrue();
        assertThat(options.minClaimedAmount()).isEqualByComparingTo("500000");
        assertThat(options.claimedAmount()).isEqualByComparingTo(CLAIMED_AMOUNT);
        assertThat(options.firms()).singleElement()
                .satisfies(option -> assertThat(option.name()).isEqualTo("Estudio Verifica S.R.L."));
    }

    /** The threshold keeps the assessment from costing more than the claim. */
    @Test
    void options_isNotEligible_whenTheClaimedAmountIsBelowTheThreshold() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseAwaitingReview()));
        givenPolicy(new BigDecimal("2000000"));
        when(expertFirmRepository.findAvailableForBranch(BRANCH_ID, ProviderType.ESTUDIO_LIQUIDADOR))
                .thenReturn(List.of(firm(3L, "Estudio Verifica S.R.L.", "verifica@example.com")));

        DerivationOptionsResponse options = expertAssessmentService.options(CASE_ID, ProviderType.ESTUDIO_LIQUIDADOR);

        assertThat(options.eligible()).isFalse();
        // The firms are still returned: the screen explains why it can't derive, and needs both
        // amounts for that, not just the verdict.
        assertThat(options.firms()).hasSize(1);
        assertThat(options.minClaimedAmount()).isEqualByComparingTo("2000000");
    }

    /**
     * An insurer that never configured the rule doesn't derive (e.g. extended warranty, where the
     * assessment costs more than the device).
     */
    @Test
    void options_isNotEligible_whenTheInsurerDoesNotDeriveThisBranch() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseAwaitingReview()));
        when(rulesServiceClient.expertDerivationPolicy(BRANCH_ID))
                .thenReturn(new RulesServiceClient.ExpertDerivationPolicy(false, null, null));
        when(expertFirmRepository.findAvailableForBranch(BRANCH_ID, ProviderType.ESTUDIO_LIQUIDADOR))
                .thenReturn(List.of(firm(3L, "Estudio Verifica S.R.L.", "verifica@example.com")));

        DerivationOptionsResponse options = expertAssessmentService.options(CASE_ID, ProviderType.ESTUDIO_LIQUIDADOR);

        assertThat(options.eligible()).isFalse();
        assertThat(options.minClaimedAmount()).isNull();
    }

    @Test
    void options_isNotEligible_whenTheCatalogIsEmpty() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseAwaitingReview()));
        givenPolicy(new BigDecimal("500000"));
        when(expertFirmRepository.findAvailableForBranch(BRANCH_ID, ProviderType.ESTUDIO_LIQUIDADOR)).thenReturn(List.of());

        assertThat(expertAssessmentService.options(CASE_ID, ProviderType.ESTUDIO_LIQUIDADOR).eligible()).isFalse();
    }

    /** Enforced in the backend too: hiding the button is only a suggestion. */
    @Test
    void derive_refusesWhenTheAmountIsBelowTheInsurersThreshold() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseAwaitingReview()));
        when(claimsAnalystRepository.findByEmail(ANALYST_EMAIL)).thenReturn(Optional.of(analyst()));
        givenPolicy(new BigDecimal("2000000"));

        assertThatThrownBy(() -> expertAssessmentService.derive(CASE_ID,
                new DeriveToExpertRequest(3L, "motivo"), ProviderType.ESTUDIO_LIQUIDADOR))
                .isInstanceOf(DerivationNotAllowedException.class);

        verify(expertAssessmentRepository, never()).save(any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void deriveToRepair_movesTheCaseToPendingRepair_withoutAskingForTheExpertAssessmentThreshold() {
        Case caseRecord = caseAwaitingReview();
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        when(claimsAnalystRepository.findByEmail(ANALYST_EMAIL)).thenReturn(Optional.of(analyst()));
        givenRepairPolicy(CLAIM_CAUSE_ID);
        when(expertFirmRepository.findAvailableForBranch(BRANCH_ID, ProviderType.SERVICIO_TECNICO))
                .thenReturn(List.of(firm(5L, "Service Celular Once", "service@example.com")));
        when(expertAssessmentRepository.save(any(ExpertAssessment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExpertAssessmentResponse response = expertAssessmentService.derive(CASE_ID,
                new DeriveToExpertRequest(5L, "Pantalla rota, cotizar reparación"), ProviderType.SERVICIO_TECNICO);

        assertThat(response.providerType()).isEqualTo(ProviderType.SERVICIO_TECNICO);
        verify(caseStatusService).transition(eq(caseRecord), eq(CaseStatus.PENDING_REPAIR),
                eq(StatusChangeActor.ANALYST), any());
        verify(rulesServiceClient, never()).expertDerivationPolicy(any());
    }

    @Test
    void optionsForRepair_offerTheCatalogWhenTheClaimCauseAdmitsRepair() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseAwaitingReview()));
        givenRepairPolicy(CLAIM_CAUSE_ID);
        when(expertFirmRepository.findAvailableForBranch(BRANCH_ID, ProviderType.SERVICIO_TECNICO))
                .thenReturn(List.of(firm(5L, "Service Celular Once", "service@example.com")));

        DerivationOptionsResponse options = expertAssessmentService.options(CASE_ID, ProviderType.SERVICIO_TECNICO);

        assertThat(options.eligible()).isTrue();
        assertThat(options.minClaimedAmount()).isNull();
        verify(rulesServiceClient, never()).expertDerivationPolicy(any());
    }

    /** A stolen phone has nothing to repair: the option stays closed whoever is in the catalog. */
    @Test
    void optionsForRepair_areClosedWhenTheClaimCauseDoesNotAdmitRepair() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseAwaitingReview()));
        givenRepairPolicy(1L);

        DerivationOptionsResponse options = expertAssessmentService.options(CASE_ID, ProviderType.SERVICIO_TECNICO);

        assertThat(options.eligible()).isFalse();
        assertThat(options.firms()).isEmpty();
        verify(expertFirmRepository, never()).findAvailableForBranch(any(), any());
    }

    /** Enforced on derive too: hiding the button is a suggestion, not a rule. */
    @Test
    void deriveToRepair_refusesAClaimCauseThatDoesNotAdmitRepair() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseAwaitingReview()));
        when(claimsAnalystRepository.findByEmail(ANALYST_EMAIL)).thenReturn(Optional.of(analyst()));
        givenRepairPolicy(1L);

        assertThatThrownBy(() -> expertAssessmentService.derive(CASE_ID,
                new DeriveToExpertRequest(5L, "motivo"), ProviderType.SERVICIO_TECNICO))
                .isInstanceOf(DerivationNotAllowedException.class);

        verify(expertAssessmentRepository, never()).save(any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
        verify(expertNotificationService, never()).notifyDerivation(any(), any());
    }

    @Test
    void receiveRepairReport_keepsItApartFromTheExpertReport_andLeavesNoFraudRecord() {
        Case caseRecord = caseInStatus(CaseStatus.PENDING_REPAIR);
        ExpertAssessment repair = awaitingAssessment();
        repair.setProviderType(ProviderType.SERVICIO_TECNICO);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        when(expertAssessmentRepository.findByCaseIdAndProviderType(CASE_ID, ProviderType.SERVICIO_TECNICO))
                .thenReturn(Optional.of(repair));
        when(caseDocumentRepository.findByCaseIdAndType(CASE_ID, "repair_report")).thenReturn(Optional.empty());
        when(caseDocumentRepository.save(any(CaseDocument.class))).thenAnswer(invocation -> {
            CaseDocument saved = invocation.getArgument(0);
            saved.setId(43L);
            return saved;
        });
        when(expertAssessmentRepository.save(any(ExpertAssessment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExpertAssessmentResponse response = expertAssessmentService.receiveRepairReport(CASE_ID,
                RepairOutcome.IRREPARABLE, "Placa dañada", null,
                new MockMultipartFile("report", "service.pdf", "application/pdf", "PDF".getBytes()));

        assertThat(response.repairOutcome()).isEqualTo(RepairOutcome.IRREPARABLE);
        assertThat(response.verdict()).isNull();
        assertThat(response.repairCost()).isNull();
        ArgumentCaptor<CaseDocument> document = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).save(document.capture());
        assertThat(document.getValue().getType()).isEqualTo("repair_report");
        verify(caseStatusService).transition(eq(caseRecord), eq(CaseStatus.PENDING_ANALYST_REVIEW),
                eq(StatusChangeActor.ANALYST), any());
        verify(fraudRecordService, never()).registerFromExpertReport(any(), any());
    }

    /**
     * The repair quote goes to its own column, NOT the expert's: one answers what the claim is worth,
     * the other what the repair costs, and the settlement needs to know which one it's reading.
     */
    @Test
    void receiveRepairReport_storesTheQuoteInItsOwnColumn() {
        Case caseRecord = caseInStatus(CaseStatus.PENDING_REPAIR);
        ExpertAssessment repair = awaitingAssessment();
        repair.setProviderType(ProviderType.SERVICIO_TECNICO);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        when(expertAssessmentRepository.findByCaseIdAndProviderType(CASE_ID, ProviderType.SERVICIO_TECNICO))
                .thenReturn(Optional.of(repair));
        when(caseDocumentRepository.findByCaseIdAndType(CASE_ID, "repair_report")).thenReturn(Optional.empty());
        when(caseDocumentRepository.save(any(CaseDocument.class))).thenAnswer(invocation -> {
            CaseDocument saved = invocation.getArgument(0);
            saved.setId(44L);
            return saved;
        });
        when(expertAssessmentRepository.save(any(ExpertAssessment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExpertAssessmentResponse response = expertAssessmentService.receiveRepairReport(CASE_ID,
                RepairOutcome.QUOTE_SENT, "Cambio de módulo", new BigDecimal("180000.00"),
                new MockMultipartFile("report", "presupuesto.pdf", "application/pdf", "PDF".getBytes()));

        assertThat(response.repairCost()).isEqualByComparingTo("180000.00");
        assertThat(response.indemnifiableAmount()).isNull();
    }

    @Test
    void receiveRepairReport_rejectsAQuoteWithNoAmount() {
        when(caseRepository.findById(CASE_ID))
                .thenReturn(Optional.of(caseInStatus(CaseStatus.PENDING_REPAIR)));

        assertThatThrownBy(() -> expertAssessmentService.receiveRepairReport(CASE_ID,
                RepairOutcome.QUOTE_SENT, "Cambio de módulo", null,
                new MockMultipartFile("report", "x.pdf", "application/pdf", "PDF".getBytes())))
                .isInstanceOf(InvalidRepairReportException.class);

        verify(expertAssessmentRepository, never()).save(any());
    }

    /** Nothing was repaired, so nothing was charged: an amount there is a mistake, not a low quote. */
    @Test
    void receiveRepairReport_rejectsACostOnAnIrreparableItem() {
        when(caseRepository.findById(CASE_ID))
                .thenReturn(Optional.of(caseInStatus(CaseStatus.PENDING_REPAIR)));

        assertThatThrownBy(() -> expertAssessmentService.receiveRepairReport(CASE_ID,
                RepairOutcome.IRREPARABLE, "No tiene arreglo", new BigDecimal("180000.00"),
                new MockMultipartFile("report", "x.pdf", "application/pdf", "PDF".getBytes())))
                .isInstanceOf(InvalidRepairReportException.class);

        verify(expertAssessmentRepository, never()).save(any());
    }

    /**
     * A shop that already repaired the item charges for the work, and that amount is what gets
     * settled; without it the settlement would propose paying zero.
     */
    @Test
    void receiveRepairReport_takesTheInvoiceOfAnAlreadyRepairedItem() {
        Case caseRecord = caseInStatus(CaseStatus.PENDING_REPAIR);
        ExpertAssessment repair = awaitingAssessment();
        repair.setProviderType(ProviderType.SERVICIO_TECNICO);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        when(expertAssessmentRepository.findByCaseIdAndProviderType(CASE_ID, ProviderType.SERVICIO_TECNICO))
                .thenReturn(Optional.of(repair));
        when(caseDocumentRepository.findByCaseIdAndType(CASE_ID, "repair_report")).thenReturn(Optional.empty());
        when(caseDocumentRepository.save(any(CaseDocument.class))).thenAnswer(invocation -> {
            CaseDocument saved = invocation.getArgument(0);
            saved.setId(45L);
            return saved;
        });
        when(expertAssessmentRepository.save(any(ExpertAssessment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExpertAssessmentResponse response = expertAssessmentService.receiveRepairReport(CASE_ID,
                RepairOutcome.REPAIRED, "Cambio de módulo", new BigDecimal("210000.00"),
                new MockMultipartFile("report", "factura.pdf", "application/pdf", "PDF".getBytes()));

        assertThat(response.repairCost()).isEqualByComparingTo("210000.00");
    }

    /** The shop's invoice doesn't always come with the report. */
    @Test
    void receiveRepairReport_acceptsARepairWithNoInvoiceYet() {
        Case caseRecord = caseInStatus(CaseStatus.PENDING_REPAIR);
        ExpertAssessment repair = awaitingAssessment();
        repair.setProviderType(ProviderType.SERVICIO_TECNICO);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        when(expertAssessmentRepository.findByCaseIdAndProviderType(CASE_ID, ProviderType.SERVICIO_TECNICO))
                .thenReturn(Optional.of(repair));
        when(caseDocumentRepository.findByCaseIdAndType(CASE_ID, "repair_report")).thenReturn(Optional.empty());
        when(caseDocumentRepository.save(any(CaseDocument.class))).thenAnswer(invocation -> {
            CaseDocument saved = invocation.getArgument(0);
            saved.setId(46L);
            return saved;
        });
        when(expertAssessmentRepository.save(any(ExpertAssessment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExpertAssessmentResponse response = expertAssessmentService.receiveRepairReport(CASE_ID,
                RepairOutcome.REPAIRED, "Sin factura todavía", null,
                new MockMultipartFile("report", "informe.pdf", "application/pdf", "PDF".getBytes()));

        assertThat(response.repairCost()).isNull();
    }

    /**
     * Deriving mails an external expert and leaves the case where its owner can no longer decide, so
     * {@code @PreAuthorize}'s role check isn't enough, same as approve/reject.
     */
    @Test
    void derive_refusesWhenNobodyOwnsTheCase() {
        Case caseRecord = caseAwaitingReview();
        caseRecord.setAnalyst(null);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        when(claimsAnalystRepository.findByEmail(ANALYST_EMAIL)).thenReturn(Optional.of(analyst()));

        assertThatThrownBy(() -> expertAssessmentService.derive(CASE_ID,
                new DeriveToExpertRequest(3L, "motivo"), ProviderType.ESTUDIO_LIQUIDADOR))
                .isInstanceOf(CaseNotAssignedException.class);

        verify(expertAssessmentRepository, never()).save(any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
        verify(expertNotificationService, never()).notifyDerivation(any(), any());
    }

    @Test
    void derive_refusesWhenTheCaseBelongsToAnotherAnalyst() {
        Case caseRecord = caseAwaitingReview();
        caseRecord.setAnalyst(ClaimsAnalyst.builder().id(99L).name("Otro").surname("Analista").build());
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        when(claimsAnalystRepository.findByEmail(ANALYST_EMAIL)).thenReturn(Optional.of(analyst()));

        assertThatThrownBy(() -> expertAssessmentService.derive(CASE_ID,
                new DeriveToExpertRequest(3L, "motivo"), ProviderType.ESTUDIO_LIQUIDADOR))
                .isInstanceOf(CaseAssignedToAnotherAnalystException.class);

        verify(expertAssessmentRepository, never()).save(any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
        // What matters most: nothing reached the expert. The mail leaves the system.
        verify(expertNotificationService, never()).notifyDerivation(any(), any());
    }

    private void givenCaseAndAnalyst(Case caseRecord) {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        when(claimsAnalystRepository.findByEmail(ANALYST_EMAIL)).thenReturn(Optional.of(analyst()));
        givenPolicy(new BigDecimal("500000"));
    }

    private void givenPolicy(BigDecimal minClaimedAmount) {
        when(rulesServiceClient.expertDerivationPolicy(BRANCH_ID))
                .thenReturn(new RulesServiceClient.ExpertDerivationPolicy(true, minClaimedAmount, 4L));
    }

    private void givenRepairPolicy(Long... admittedClaimCauseIds) {
        when(rulesServiceClient.repairDerivationPolicy(BRANCH_ID))
                .thenReturn(new RulesServiceClient.RepairDerivationPolicy(true, List.of(admittedClaimCauseIds), 9L));
    }

    private Case caseAwaitingReview() {
        return caseInStatus(CaseStatus.PENDING_ANALYST_REVIEW);
    }

    private Case caseInStatus(CaseStatus status) {
        // The branch needs an id because the service filters the expert catalog by branch, and the
        // fixture builds it without one.
        ClaimCause cause = CaseFixtures.claimCause("Celulares", "Robo en vía pública");
        cause.setBranch(Branch.builder().id(BRANCH_ID).name("Celulares").build());
        cause.setId(CLAIM_CAUSE_ID);
        return Case.builder()
                .id(CASE_ID)
                .claimCause(cause)
                .claimedAmount(CLAIMED_AMOUNT)
                .currentStatus(CaseStates.of(status))
                // Owned: deriving belongs to the assigned analyst, like deciding.
                .analyst(analyst())
                .build();
    }

    private ExpertAssessment awaitingAssessment() {
        return ExpertAssessment.builder()
                .id(1L)
                .caseId(CASE_ID)
                .expertName("Estudio Verifica S.R.L.")
                .expertEmail("verifica@example.com")
                .reason("Banda CRÍTICA")
                .derivedBy(analyst())
                .derivedAt(Instant.parse("2026-08-15T09:00:00Z"))
                .build();
    }

    private ExpertFirm firm(Long id, String name, String email) {
        return ExpertFirm.builder().id(id).name(name).email(email).active(true).build();
    }

    private ClaimsAnalyst analyst() {
        return ClaimsAnalyst.builder().id(1L).name("Ana").surname("Pérez").build();
    }
}
