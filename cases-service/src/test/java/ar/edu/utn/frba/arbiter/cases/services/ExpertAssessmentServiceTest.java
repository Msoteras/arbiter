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

    /** El guardián de la máquina de estados se prueba aparte, en CaseStatusServiceTest. */
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

        // Copiados, no leídos por la asociación: si mañana editan el catálogo, el registro de
        // quién peritó ESTE siniestro no cambia atrás.
        assertThat(response.expertName()).isEqualTo("Estudio Verifica S.R.L.");
        assertThat(response.expertEmail()).isEqualTo("verifica@example.com");
        assertThat(response.reason()).isEqualTo("Banda CRÍTICA e imagen reutilizada");
        assertThat(response.notified()).isTrue();
        assertThat(response.verdict()).isNull();

        verify(caseStatusService).transition(eq(caseRecord), eq(CaseStatus.PENDING_EXPERT_REPORT),
                eq(StatusChangeActor.ANALYST), any());
    }

    /**
     * El mail es best-effort, igual que el resto de las notificaciones: que SendGrid falle no
     * puede deshacer una derivación que ya ocurrió. Pero tiene que notarse — un expediente
     * esperando a un perito al que nadie le avisó es invisible sin esto.
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

    /** Un perito de otro ramo (o inactivo) no está en la lista, así que no se puede elegir. */
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
     * El perito ya probó el hecho: pedirle al analista un segundo clic para que llegue al legajo de
     * la persona agregaba un paso que se olvida, y olvidarlo deja a alguien sin marca con un informe
     * que dice lo contrario.
     */
    @Test
    void receiveReport_confirmingFraud_recordsItOnTheInsured() {
        givenAReportCanBeFiled();

        expertAssessmentService.receiveReport(CASE_ID, ExpertVerdict.FRAUD_CONFIRMED,
                "El equipo ya estaba dañado antes de la vigencia", null, new MockMultipartFile("report", "informe.pdf", "application/pdf", "PDF".getBytes()));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(fraudRecordService).registerFromExpertReport(eq(CASE_ID), reason.capture());
        // El motivo tiene que decir quién lo encontró y qué escribió: es lo que se lee años después
        // al lado de la marca sobre la persona.
        assertThat(reason.getValue())
                .contains("Estudio Verifica S.R.L.")
                .contains("El equipo ya estaba dañado antes de la vigencia");
    }

    /** Descartado o no concluyente no dejan nada sobre la persona. */
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
     * El informe es evidencia: no se pisa. El estado del expediente no alcanza para detectarlo —
     * un caso que ya volvió a revisión con su informe está en el mismo estado que uno que nunca
     * se derivó.
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

    /** El umbral es lo que hace que el peritaje no salga más caro que el siniestro. */
    @Test
    void options_isNotEligible_whenTheClaimedAmountIsBelowTheThreshold() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseAwaitingReview()));
        givenPolicy(new BigDecimal("2000000"));
        when(expertFirmRepository.findAvailableForBranch(BRANCH_ID, ProviderType.ESTUDIO_LIQUIDADOR))
                .thenReturn(List.of(firm(3L, "Estudio Verifica S.R.L.", "verifica@example.com")));

        DerivationOptionsResponse options = expertAssessmentService.options(CASE_ID, ProviderType.ESTUDIO_LIQUIDADOR);

        assertThat(options.eligible()).isFalse();
        // Los peritos viajan igual: la pantalla explica por qué no se puede, y para eso necesita
        // los dos montos, no solo el veredicto.
        assertThat(options.firms()).hasSize(1);
        assertThat(options.minClaimedAmount()).isEqualByComparingTo("2000000");
    }

    /**
     * Una aseguradora que nunca configuró la regla no deriva. Es el caso real de una compañía de
     * garantía extendida: el peritaje cuesta más que el equipo.
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

    /** Habilitado por regla pero sin peritos cargados: igual no hay a quién derivar. */
    @Test
    void options_isNotEligible_whenTheCatalogIsEmpty() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseAwaitingReview()));
        givenPolicy(new BigDecimal("500000"));
        when(expertFirmRepository.findAvailableForBranch(BRANCH_ID, ProviderType.ESTUDIO_LIQUIDADOR)).thenReturn(List.of());

        assertThat(expertAssessmentService.options(CASE_ID, ProviderType.ESTUDIO_LIQUIDADOR).eligible()).isFalse();
    }

    /**
     * El umbral se aplica en el backend y no solo escondiendo el botón: una regla que aplica el
     * frontend es una sugerencia.
     */
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
    void deriveToRepair_movesTheCaseToPendingRepair_withoutAskingForThePeritajeThreshold() {
        Case caseRecord = caseAwaitingReview();
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        when(claimsAnalystRepository.findByEmail(ANALYST_EMAIL)).thenReturn(Optional.of(analyst()));
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
    void optionsForRepair_dependOnlyOnTheCatalog() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseAwaitingReview()));
        when(expertFirmRepository.findAvailableForBranch(BRANCH_ID, ProviderType.SERVICIO_TECNICO))
                .thenReturn(List.of(firm(5L, "Service Celular Once", "service@example.com")));

        DerivationOptionsResponse options = expertAssessmentService.options(CASE_ID, ProviderType.SERVICIO_TECNICO);

        assertThat(options.eligible()).isTrue();
        assertThat(options.minClaimedAmount()).isNull();
        verify(rulesServiceClient, never()).expertDerivationPolicy(any());
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
        assertThat(response.quotedAmount()).isNull();
        ArgumentCaptor<CaseDocument> document = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).save(document.capture());
        assertThat(document.getValue().getType()).isEqualTo("repair_report");
        verify(caseStatusService).transition(eq(caseRecord), eq(CaseStatus.PENDING_ANALYST_REVIEW),
                eq(StatusChangeActor.ANALYST), any());
        verify(fraudRecordService, never()).registerFromExpertReport(any(), any());
    }

    /**
     * El presupuesto del taller va a su propia columna y NO a la del perito: los dos números
     * contestan preguntas distintas —cuánto vale el siniestro contra cuánto sale el arreglo— y
     * mezclarlos haría que la liquidación no supiera cuál está leyendo.
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

        assertThat(response.quotedAmount()).isEqualByComparingTo("180000.00");
        assertThat(response.indemnifiableAmount()).isNull();
    }

    /** Decir que mandaron presupuesto sin decir cuánto no contesta la pregunta que se les hizo. */
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

    /** Un equipo reparado o irreparable no tiene presupuesto detrás: el importe ahí es un error. */
    @Test
    void receiveRepairReport_rejectsAnAmountWithoutAQuote() {
        when(caseRepository.findById(CASE_ID))
                .thenReturn(Optional.of(caseInStatus(CaseStatus.PENDING_REPAIR)));

        assertThatThrownBy(() -> expertAssessmentService.receiveRepairReport(CASE_ID,
                RepairOutcome.REPAIRED, "Quedó como nuevo", new BigDecimal("180000.00"),
                new MockMultipartFile("report", "x.pdf", "application/pdf", "PDF".getBytes())))
                .isInstanceOf(InvalidRepairReportException.class);

        verify(expertAssessmentRepository, never()).save(any());
    }

    /**
     * Derivar es del dueño del expediente, no de cualquier analista del tenant. No es un detalle de
     * permisos: la derivación le manda un mail a un perito externo y deja el caso en
     * PENDING_EXPERT_REPORT, donde el analista asignado ya no puede decidir. {@code @PreAuthorize}
     * solo valida el rol; esto valida el expediente. Mismo chequeo que aprobar/rechazar, que lo
     * tenía desde antes — la derivación se sumó después y quedó sin él.
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
        // Lo que más importa: al perito no le llegó nada. El mail sale del sistema.
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

    private Case caseAwaitingReview() {
        return caseInStatus(CaseStatus.PENDING_ANALYST_REVIEW);
    }

    private Case caseInStatus(CaseStatus status) {
        // El branch necesita id porque el servicio filtra el catálogo de peritos por ramo, y el
        // fixture lo arma sin id (los tests que lo usan no lo miran).
        ClaimCause cause = CaseFixtures.claimCause("Celulares", "Robo en vía pública");
        cause.setBranch(Branch.builder().id(BRANCH_ID).name("Celulares").build());
        return Case.builder()
                .id(CASE_ID)
                .claimCause(cause)
                .claimedAmount(CLAIMED_AMOUNT)
                .currentStatus(CaseStates.of(status))
                // Con dueño: derivar es del analista asignado, igual que decidir.
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
