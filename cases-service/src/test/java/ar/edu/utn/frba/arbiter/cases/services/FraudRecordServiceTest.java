package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.RegisterFraudRecordRequest;
import ar.edu.utn.frba.arbiter.cases.exceptions.FraudRecordNotAllowedException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ExpertAssessmentRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordRequest;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordResponse;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;
import ar.edu.utn.frba.arbiter.common.enums.FraudRecordSource;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudRecordServiceTest {

    private static final Long CASE_ID = 7L;
    private static final String DNI = "40.123.456";
    private static final String ANALYST_EMAIL = "analista.arbiter@gmail.com";
    private static final String REASON = "El peritaje verificó que el equipo denunciado nunca existió";

    @Mock
    private CaseRepository caseRepository;

    @Mock
    private ExpertAssessmentRepository expertAssessmentRepository;

    @Mock
    private ClaimsAnalystRepository claimsAnalystRepository;

    @Mock
    private ClaimsAnalysisClient classificationClient;

    @InjectMocks
    private FraudRecordService fraudRecordService;

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
    void expertBackedRecord_flagsTheCase_andSendsTheExpertAssessmentAlong() {
        Case caseRecord = caseInStatus(CaseStatus.PENDING_ANALYST_REVIEW);
        givenCaseAndAnalyst(caseRecord);
        when(expertAssessmentRepository.findByCaseId(CASE_ID))
                .thenReturn(Optional.of(assessmentWith(ExpertVerdict.FRAUD_CONFIRMED)));
        when(classificationClient.registerFraudRecord(any())).thenReturn(response());

        fraudRecordService.register(CASE_ID, new RegisterFraudRecordRequest(
                FraudRecordSource.EXPERT_BACKED, REASON));

        ArgumentCaptor<FraudRecordRequest> sent = ArgumentCaptor.forClass(FraudRecordRequest.class);
        verify(classificationClient).registerFraudRecord(sent.capture());
        assertThat(sent.getValue().insuredDni()).isEqualTo(DNI);
        assertThat(sent.getValue().expertAssessmentId()).isEqualTo(9L);
        // El analista sale del token, nunca del body: si viniera del cliente, cualquiera podría
        // colgarle el antecedente a otro.
        assertThat(sent.getValue().declaredByAnalystId()).isEqualTo(1L);
        assertThat(sent.getValue().declaredByAnalystName()).isEqualTo("Ana Pérez");

        // La columna que el DER tenía desde siempre y nadie escribía.
        assertThat(caseRecord.isFraudDetermined()).isTrue();
        verify(caseRepository).save(caseRecord);
    }

    /**
     * La diferencia entre los dos orígenes es que uno mueve el score. Si alcanzara con elegir la
     * opción más fuerte en el request, no valdría nada.
     */
    @Test
    void expertBackedRecord_isRefusedWhenTheReportDidNotConfirmTheFraud() {
        Case caseRecord = caseInStatus(CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        when(expertAssessmentRepository.findByCaseId(CASE_ID))
                .thenReturn(Optional.of(assessmentWith(ExpertVerdict.INCONCLUSIVE)));

        assertThatThrownBy(() -> fraudRecordService.register(CASE_ID,
                new RegisterFraudRecordRequest(FraudRecordSource.EXPERT_BACKED, REASON)))
                .isInstanceOf(FraudRecordNotAllowedException.class);

        assertThat(caseRecord.isFraudDetermined()).isFalse();
        verify(classificationClient, never()).registerFraudRecord(any());
    }

    @Test
    void expertBackedRecord_isRefusedWhenTheCaseWasNeverDerived() {
        Case caseRecord = caseInStatus(CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        when(expertAssessmentRepository.findByCaseId(CASE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fraudRecordService.register(CASE_ID,
                new RegisterFraudRecordRequest(FraudRecordSource.EXPERT_BACKED, REASON)))
                .isInstanceOf(FraudRecordNotAllowedException.class);

        verify(classificationClient, never()).registerFraudRecord(any());
    }

    /** La lista negra: sin peritaje, sin referencia a ninguno, y no la mira el motor. */
    @Test
    void analystDeclaredRecord_needsNoExpertAssessment() {
        Case caseRecord = caseInStatus(CaseStatus.REJECTED);
        givenCaseAndAnalyst(caseRecord);
        when(classificationClient.registerFraudRecord(any())).thenReturn(response());

        fraudRecordService.register(CASE_ID, new RegisterFraudRecordRequest(
                FraudRecordSource.ANALYST_DECLARED, "El asegurado reconoció por escrito que el robo no ocurrió"));

        ArgumentCaptor<FraudRecordRequest> sent = ArgumentCaptor.forClass(FraudRecordRequest.class);
        verify(classificationClient).registerFraudRecord(sent.capture());
        assertThat(sent.getValue().expertAssessmentId()).isNull();
        verify(expertAssessmentRepository, never()).findByCaseId(any());
    }

    /** Pagar el siniestro y registrarlo como fraude se contradicen. */
    @Test
    void anApprovedCaseCannotProduceAFraudRecord() {
        Case caseRecord = caseInStatus(CaseStatus.APPROVED);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));

        assertThatThrownBy(() -> fraudRecordService.register(CASE_ID,
                new RegisterFraudRecordRequest(FraudRecordSource.ANALYST_DECLARED, REASON)))
                .isInstanceOf(FraudRecordNotAllowedException.class);

        verify(classificationClient, never()).registerFraudRecord(any());
    }

    /** La evidencia que se está esperando todavía no llegó. */
    @Test
    void aCaseAwaitingTheExpertReportCannotProduceAFraudRecordYet() {
        Case caseRecord = caseInStatus(CaseStatus.PENDING_EXPERT_REPORT);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));

        assertThatThrownBy(() -> fraudRecordService.register(CASE_ID,
                new RegisterFraudRecordRequest(FraudRecordSource.EXPERT_BACKED, REASON)))
                .isInstanceOf(FraudRecordNotAllowedException.class);

        verify(classificationClient, never()).registerFraudRecord(any());
    }

    @Test
    void insuredRecords_asksForTheInsuredBehindTheCase() {
        when(caseRepository.findById(CASE_ID))
                .thenReturn(Optional.of(caseInStatus(CaseStatus.PENDING_ANALYST_REVIEW)));
        when(classificationClient.fraudRecordsOf(DNI)).thenReturn(List.of(response()));

        assertThat(fraudRecordService.insuredRecords(CASE_ID)).hasSize(1);
        verify(classificationClient).fraudRecordsOf(DNI);
    }

    private void givenCaseAndAnalyst(Case caseRecord) {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(caseRecord));
        when(claimsAnalystRepository.findByEmail(ANALYST_EMAIL)).thenReturn(Optional.of(analyst()));
    }

    private Case caseInStatus(CaseStatus status) {
        return Case.builder()
                .id(CASE_ID)
                .claimCause(CaseFixtures.claimCause("Celulares", "Robo en vía pública"))
                .insured(CaseFixtures.insured(DNI, "Julián", "Pérez"))
                .currentStatus(CaseStates.of(status))
                .build();
    }

    private ExpertAssessment assessmentWith(ExpertVerdict verdict) {
        return ExpertAssessment.builder()
                .id(9L)
                .caseId(CASE_ID)
                .expertName("Estudio Verifica S.R.L.")
                .expertEmail("verifica@example.com")
                .reason("Banda CRÍTICA")
                .derivedBy(analyst())
                .derivedAt(Instant.parse("2026-08-15T09:00:00Z"))
                .verdict(verdict)
                .build();
    }

    private ClaimsAnalyst analyst() {
        return ClaimsAnalyst.builder().id(1L).name("Ana").surname("Pérez").build();
    }

    private FraudRecordResponse response() {
        return new FraudRecordResponse(1L, DNI, CASE_ID, FraudRecordSource.EXPERT_BACKED, REASON,
                9L, "Ana Pérez", Instant.now(), true, true);
    }
}
