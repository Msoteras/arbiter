package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.DocumentAnalysisSummary;
import ar.edu.utn.frba.arbiter.cases.dto.DocumentCorrectionRequest;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseAssignedToAnotherAnalystException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotAssignedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DocumentCorrectionNotAllowedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DocumentNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.DocumentAnalysisCorrection;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExtractedField;
import ar.edu.utn.frba.arbiter.cases.models.entities.RequestedDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentAnalysisRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.DocumentAnalysisCorrectionRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.DocumentTypeRepository;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import ar.edu.utn.frba.arbiter.common.models.entities.DocumentType;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentCorrectionServiceTest {

    private static final String ANALYST_EMAIL = "analista@arbiter.test";
    private static final Long CASE_ID = 26L;
    private static final Long DOCUMENT_ID = 90L;

    @Mock private CaseRepository caseRepository;
    @Mock private CaseDocumentRepository caseDocumentRepository;
    @Mock private CaseDocumentAnalysisRepository documentAnalysisRepository;
    @Mock private DocumentAnalysisCorrectionRepository correctionRepository;
    @Mock private ClaimsAnalystRepository claimsAnalystRepository;
    @Mock private DocumentTypeRepository documentTypeRepository;
    @Mock private CaseStatusService caseStatusService;

    @InjectMocks
    private DocumentCorrectionService service;

    private final ClaimsAnalyst analyst = ClaimsAnalyst.builder().id(7L).name("Lucas").surname("Gómez")
            .email(ANALYST_EMAIL).build();

    private Case claim;
    private CaseDocument quote;

    @BeforeEach
    void setUp() {
        claim = Case.builder()
                .id(CASE_ID)
                .analyst(analyst)
                .currentStatus(CaseState.builder().name("PENDING_ANALYST_REVIEW").isFinal(false).build())
                .riskScore(42.0)
                .riskBand(RiskBand.MEDIUM)
                .build();
        quote = CaseDocument.builder().id(DOCUMENT_ID).caseId(CASE_ID).type("repair_quote").build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ANALYST_EMAIL, "n/a", List.of()));
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(claim));
        when(claimsAnalystRepository.findByEmail(ANALYST_EMAIL)).thenReturn(Optional.of(analyst));
        when(caseDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(quote));
        when(documentAnalysisRepository.findByCaseId(CASE_ID)).thenReturn(List.of(reading(List.of())));
        when(documentTypeRepository.findByCode("repair_quote"))
                .thenReturn(Optional.of(DocumentType.builder().code("repair_quote")
                        .name("Presupuesto de reparación").build()));
        when(correctionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void keepsTheModelsReadingAsThePreviousValueAndLeavesANoteWithoutMovingTheCase() {
        DocumentAnalysisSummary.Correction result = service.correct(CASE_ID, DOCUMENT_ID,
                new DocumentCorrectionRequest(ExtractedField.AMOUNT, null, " 95000 "));

        DocumentAnalysisCorrection saved = savedCorrection();
        assertThat(saved.getCaseDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(saved.getField()).isEqualTo(ExtractedField.AMOUNT);
        assertThat(saved.getFieldName()).isNull();
        assertThat(saved.getPreviousValue()).isEqualTo("120000.00");
        assertThat(saved.getNewValue()).isEqualTo("95000.00");
        assertThat(saved.getAnalyst()).isEqualTo(analyst);
        assertThat(result.analystName()).isEqualTo("Lucas Gómez");

        String note = note();
        assertThat(note).startsWith("Lucas Gómez corrigió el importe en «Presupuesto de reparación»: ");
        assertThat(note).contains("120.000,00").contains("→").contains("95.000,00");
        verify(caseStatusService, never()).transition(any(), any(), any(), anyString());
        verify(caseStatusService, never()).transition(any(), any(), any(), anyString(), any());
        assertThat(claim.getRiskScore()).isEqualTo(42.0);
        assertThat(claim.getRiskBand()).isEqualTo(RiskBand.MEDIUM);
    }

    @Test
    void takesTheLatestCorrectionAsThePreviousValue() {
        when(documentAnalysisRepository.findByCaseId(CASE_ID)).thenReturn(List.of(reading(List.of(
                correction(ExtractedField.AMOUNT, null, "120000.00", "100000.00"),
                correction(ExtractedField.AMOUNT, null, "100000.00", "98000.00")))));

        service.correct(CASE_ID, DOCUMENT_ID, new DocumentCorrectionRequest(ExtractedField.AMOUNT, null, "95000"));

        assertThat(savedCorrection().getPreviousValue()).isEqualTo("98000.00");
    }

    @Test
    void fillsADateTheDocumentDidNotState() {
        service.correct(CASE_ID, DOCUMENT_ID,
                new DocumentCorrectionRequest(ExtractedField.DOCUMENT_DATE, null, "2026-11-03"));

        assertThat(savedCorrection().getPreviousValue()).isNull();
        assertThat(savedCorrection().getNewValue()).isEqualTo("2026-11-03");
        assertThat(note()).isEqualTo(
                "Lucas Gómez corrigió la fecha en «Presupuesto de reparación»: sin dato → 03/11/2026");
    }

    @Test
    void correctsAnItemAttributeTheModelRead() {
        service.correct(CASE_ID, DOCUMENT_ID,
                new DocumentCorrectionRequest(ExtractedField.ITEM_ATTRIBUTE, " IMEI ", "356789012345678"));

        assertThat(savedCorrection().getFieldName()).isEqualTo("IMEI");
        assertThat(savedCorrection().getPreviousValue()).isEqualTo("356789012345670");
        assertThat(note()).isEqualTo("Lucas Gómez corrigió «IMEI» en «Presupuesto de reparación»: "
                + "356789012345670 → 356789012345678");
    }

    @Test
    void correctsADetailAgainAfterTheRereadDroppedIt() {
        when(documentAnalysisRepository.findByCaseId(CASE_ID)).thenReturn(List.of(reading(List.of(
                correction(ExtractedField.DETAIL, "N° de presupuesto", "0001-77", "0001-78")))));

        service.correct(CASE_ID, DOCUMENT_ID,
                new DocumentCorrectionRequest(ExtractedField.DETAIL, "N° de presupuesto", "0001-79"));

        assertThat(savedCorrection().getPreviousValue()).isEqualTo("0001-78");
    }

    @Test
    void refusesAnAttributeTheModelDidNotRead() {
        assertThatThrownBy(() -> service.correct(CASE_ID, DOCUMENT_ID,
                new DocumentCorrectionRequest(ExtractedField.ITEM_ATTRIBUTE, "patente", "AB123CD")))
                .isInstanceOf(DocumentCorrectionNotAllowedException.class)
                .hasMessage("Arbiter no leyó «patente» en este documento.");
        verifyNoInteractions(correctionRepository);
    }

    @Test
    void dropsTheNameOfATypedField() {
        service.correct(CASE_ID, DOCUMENT_ID,
                new DocumentCorrectionRequest(ExtractedField.ITEM_DESCRIPTION, "marca", "Samsung A56"));

        assertThat(savedCorrection().getFieldName()).isNull();
        assertThat(savedCorrection().getPreviousValue()).isEqualTo("Samsung A55");
    }

    @Test
    void refusesTheValueTheDocumentAlreadyHas() {
        assertThatThrownBy(() -> service.correct(CASE_ID, DOCUMENT_ID,
                new DocumentCorrectionRequest(ExtractedField.AMOUNT, null, "120000")))
                .isInstanceOf(DocumentCorrectionNotAllowedException.class)
                .hasMessage("El documento ya tiene ese dato.");
        verify(caseStatusService, never()).recordNote(any(), any(), anyString(), any());
    }

    @Test
    void refusesAnAmountThatIsNotPositiveOrHasMoreThanTwoDecimals() {
        for (String amount : List.of("0", "-5", "abc", "10.555", "1234567890123")) {
            assertThatThrownBy(() -> service.correct(CASE_ID, DOCUMENT_ID,
                    new DocumentCorrectionRequest(ExtractedField.AMOUNT, null, amount)))
                    .as(amount)
                    .isInstanceOf(DocumentCorrectionNotAllowedException.class);
        }
        verifyNoInteractions(correctionRepository);
    }

    @Test
    void refusesADateThatDoesNotExist() {
        assertThatThrownBy(() -> service.correct(CASE_ID, DOCUMENT_ID,
                new DocumentCorrectionRequest(ExtractedField.DOCUMENT_DATE, null, "2026-02-31")))
                .isInstanceOf(DocumentCorrectionNotAllowedException.class)
                .hasMessage("La fecha no es válida.");
    }

    @Test
    void refusesAClosedCase() {
        claim.setCurrentStatus(CaseState.builder().name("APPROVED").isFinal(true).build());

        assertThatThrownBy(() -> service.correct(CASE_ID, DOCUMENT_ID,
                new DocumentCorrectionRequest(ExtractedField.AMOUNT, null, "95000")))
                .isInstanceOf(DocumentCorrectionNotAllowedException.class)
                .hasMessage("No se pueden corregir los documentos de un expediente cerrado.");
        verifyNoInteractions(correctionRepository);
    }

    @Test
    void refusesAnAnalystOtherThanTheAssignedOne() {
        claim.setAnalyst(ClaimsAnalyst.builder().id(8L).build());

        assertThatThrownBy(() -> service.correct(CASE_ID, DOCUMENT_ID,
                new DocumentCorrectionRequest(ExtractedField.AMOUNT, null, "95000")))
                .isInstanceOf(CaseAssignedToAnotherAnalystException.class);
        verifyNoInteractions(correctionRepository);
    }

    @Test
    void refusesAnUnassignedCase() {
        claim.setAnalyst(null);

        assertThatThrownBy(() -> service.correct(CASE_ID, DOCUMENT_ID,
                new DocumentCorrectionRequest(ExtractedField.AMOUNT, null, "95000")))
                .isInstanceOf(CaseNotAssignedException.class);
    }

    @Test
    void refusesADocumentOfAnotherCase() {
        quote.setCaseId(99L);

        assertThatThrownBy(() -> service.correct(CASE_ID, DOCUMENT_ID,
                new DocumentCorrectionRequest(ExtractedField.AMOUNT, null, "95000")))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void refusesADocumentArbiterDidNotRead() {
        when(documentAnalysisRepository.findByCaseId(CASE_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.correct(CASE_ID, DOCUMENT_ID,
                new DocumentCorrectionRequest(ExtractedField.AMOUNT, null, "95000")))
                .isInstanceOf(DocumentCorrectionNotAllowedException.class)
                .hasMessage("Arbiter no leyó este documento.");
    }

    @Test
    void namesARequestedDocumentByWhatTheAnalystAskedFor() {
        quote.setType("other_1");
        claim.setRequestedDocuments(List.of(new RequestedDocument("other_1", "Constancia del taller",
                "Falta el sello", true, RiskBand.MEDIUM)));
        when(documentTypeRepository.findByCode("other_1")).thenReturn(Optional.empty());

        service.correct(CASE_ID, DOCUMENT_ID,
                new DocumentCorrectionRequest(ExtractedField.ITEM_DESCRIPTION, null, "Samsung A56"));

        assertThat(note()).isEqualTo(
                "Lucas Gómez corrigió el bien en «Constancia del taller»: Samsung A55 → Samsung A56");
    }

    private DocumentAnalysisSummary reading(List<DocumentAnalysisSummary.Correction> corrections) {
        return new DocumentAnalysisSummary("repair_quote", DOCUMENT_ID, "Presupuesto…", null,
                new BigDecimal("120000.00"), "Samsung A55",
                List.of(new DocumentAnalysisSummary.Detail("IMEI", "356789012345670")),
                "TITULAR", List.of(), List.of(), "COMPLETE", corrections);
    }

    private static DocumentAnalysisSummary.Correction correction(ExtractedField field, String fieldName,
                                                                 String previousValue, String newValue) {
        return new DocumentAnalysisSummary.Correction(field, fieldName, previousValue, newValue,
                "Lucas Gómez", Instant.parse("2026-11-17T12:00:00Z"));
    }

    private DocumentAnalysisCorrection savedCorrection() {
        ArgumentCaptor<DocumentAnalysisCorrection> captor = ArgumentCaptor.forClass(DocumentAnalysisCorrection.class);
        verify(correctionRepository).save(captor.capture());
        return captor.getValue();
    }

    private String note() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(caseStatusService).recordNote(eq(claim), eq(StatusChangeActor.ANALYST), captor.capture(), isNull());
        return captor.getValue();
    }
}
