package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.dto.DocumentAnalysisSummary;
import ar.edu.utn.frba.arbiter.cases.dto.StatusTransitionResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseExceptionHandler;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidStatusTransitionException;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.services.CaseService;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.DeadlinePriority;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CaseController.class)
@Import(CaseExceptionHandler.class)
class CaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CaseService caseService;

    @Test
    void createCase_returns202WithBody() throws Exception {
        CaseResponse response = caseResponse(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseService.createCase(any(), any())).thenReturn(response);

        MockMultipartFile casePart = new MockMultipartFile(
                "case", "", MediaType.APPLICATION_JSON_VALUE,
                """
                {
                  "branch": "Celulares",
                  "product": "Celular Protegido Básico",
                  "claimCause": "Robo en vía pública",
                  "insuredItem": "Motorola Edge 50 Pro",
                  "insuredId": "40.123.456",
                  "policyNumber": "POL-CEL-2024-001",
                  "description": "Me robaron el celular",
                  "eventDate": "2026-06-13T19:45:00",
                  "eventLocation": "CABA",
                  "pep": false,
                  "imageConsent": false
                }
                """.getBytes()
        );

        mockMvc.perform(multipart("/api/v1/cases").file(casePart))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("PENDING_CLASSIFICATION"))
                .andExpect(jsonPath("$.branch").value("Celulares"));
    }

    @Test
    void getCase_returns200() throws Exception {
        CaseResponse response = caseResponse(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseService.getCase(1L, (String) null)).thenReturn(response);

        mockMvc.perform(get("/api/v1/cases/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("PENDING_ANALYST_REVIEW"));
    }

    @Test
    void getCase_notFound_returns404ProblemDetail() throws Exception {
        when(caseService.getCase(999L, (String) null)).thenThrow(new CaseNotFoundException(999L));

        mockMvc.perform(get("/api/v1/cases/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Case 999 not found"));
    }

    private static final Pageable DEFAULT_PAGEABLE = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"));

    @Test
    void listCases_noFilters_returnsPagedContent() throws Exception {
        CaseResponse case1 = caseResponse(2L, CaseStatus.PENDING_ANALYST_REVIEW);
        CaseResponse case2 = caseResponse(1L, CaseStatus.APPROVED);
        when(caseService.listCases(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(false), eq(false), eq(false), eq(false), eq(false), eq(DEFAULT_PAGEABLE)))
                .thenReturn(new PageImpl<>(List.of(case1, case2), DEFAULT_PAGEABLE, 2));

        mockMvc.perform(get("/api/v1/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(2))
                .andExpect(jsonPath("$.content[1].id").value(1))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void listCases_sortByResponseDeadline_isAccepted() throws Exception {
        // El orden "prioritarios primero" de la bandeja: sort por la fecha límite de respuesta.
        Pageable byDeadline = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "responseDeadline"));
        when(caseService.listCases(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(false), eq(false), eq(false), eq(false), eq(false), eq(byDeadline)))
                .thenReturn(new PageImpl<>(List.of(caseResponse(1L, CaseStatus.PENDING_ANALYST_REVIEW)),
                        byDeadline, 1));

        mockMvc.perform(get("/api/v1/cases").param("sort", "responseDeadline,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void listCases_withStatusFilter_passesStatusThrough() throws Exception {
        CaseResponse response = caseResponse(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseService.listCases(eq(CaseStatus.PENDING_ANALYST_REVIEW), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(false), eq(false), eq(false), eq(false), eq(false), eq(DEFAULT_PAGEABLE)))
                .thenReturn(new PageImpl<>(List.of(response), DEFAULT_PAGEABLE, 1));

        mockMvc.perform(get("/api/v1/cases").param("status", "PENDING_ANALYST_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("PENDING_ANALYST_REVIEW"));
    }

    @Test
    void listCases_withInsuredIdFilter_passesInsuredIdThrough() throws Exception {
        CaseResponse response = caseResponse(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseService.listCases(isNull(), isNull(), isNull(), eq("40.123.456"), isNull(), isNull(), isNull(), isNull(), isNull(), eq(false), eq(false), eq(false), eq(false), eq(false), eq(DEFAULT_PAGEABLE)))
                .thenReturn(new PageImpl<>(List.of(response), DEFAULT_PAGEABLE, 1));

        mockMvc.perform(get("/api/v1/cases").param("insuredId", "40.123.456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].insuredId").value("40.123.456"));
    }

    @Test
    void listCases_withRiskBandFilter_passesRiskBandThrough() throws Exception {
        CaseResponse response = caseResponse(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseService.listCases(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(RiskBand.HIGH), isNull(), eq(false), eq(false), eq(false), eq(false), eq(false), eq(DEFAULT_PAGEABLE)))
                .thenReturn(new PageImpl<>(List.of(response), DEFAULT_PAGEABLE, 1));

        mockMvc.perform(get("/api/v1/cases").param("riskBand", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void listCases_withAssignedToMe_passesTheLensThrough() throws Exception {
        // La lente "Míos" viaja como flag: quién es "yo" lo resuelve el service contra el token,
        // porque el id de analista es local al esquema de cada aseguradora.
        CaseResponse response = caseResponse(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseService.listCases(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(true), eq(false), eq(false), eq(false), eq(false), eq(DEFAULT_PAGEABLE)))
                .thenReturn(new PageImpl<>(List.of(response), DEFAULT_PAGEABLE, 1));

        mockMvc.perform(get("/api/v1/cases").param("assignedToMe", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void listCases_withFreeTextSearch_passesQThrough() throws Exception {
        CaseResponse response = caseResponse(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseService.listCases(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq("POL-CEL"), isNull(), isNull(), eq(false), eq(false), eq(false), eq(false), eq(false), eq(DEFAULT_PAGEABLE)))
                .thenReturn(new PageImpl<>(List.of(response), DEFAULT_PAGEABLE, 1));

        mockMvc.perform(get("/api/v1/cases").param("q", "POL-CEL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void listCases_withClaimCausePolicyNumberAndDateRange_passesFiltersThrough() throws Exception {
        CaseResponse response = caseResponse(1L, CaseStatus.APPROVED);
        when(caseService.listCases(isNull(), eq("Robo en vía pública"), eq("POL-CEL-2024-001"), isNull(),
                eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 30)), isNull(), isNull(), isNull(), eq(false), eq(false), eq(false), eq(false), eq(false), eq(DEFAULT_PAGEABLE)))
                .thenReturn(new PageImpl<>(List.of(response), DEFAULT_PAGEABLE, 1));

        mockMvc.perform(get("/api/v1/cases")
                        .param("claimCause", "Robo en vía pública")
                        .param("policyNumber", "POL-CEL-2024-001")
                        .param("eventDateFrom", "2026-06-01")
                        .param("eventDateTo", "2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void listCases_withPageParams_passesPageableThrough() throws Exception {
        CaseResponse response = caseResponse(1L, CaseStatus.APPROVED);
        Pageable pageable = PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "id"));
        when(caseService.listCases(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(false), eq(false), eq(false), eq(false), eq(false), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(response), pageable, 6));

        mockMvc.perform(get("/api/v1/cases").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(6));
    }

    @Test
    void listCases_noResults_returnsEmptyContent() throws Exception {
        when(caseService.listCases(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(false), eq(false), eq(false), eq(false), eq(false), eq(DEFAULT_PAGEABLE)))
                .thenReturn(new PageImpl<>(List.of(), DEFAULT_PAGEABLE, 0));

        mockMvc.perform(get("/api/v1/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void uploadDocuments_returns202() throws Exception {
        CaseResponse response = caseResponse(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseService.addDocumentsAndReclassify(eq(1L), any(), isNull())).thenReturn(response);

        MockMultipartFile doc = new MockMultipartFile(
                "police_report", "denuncia.pdf", MediaType.APPLICATION_PDF_VALUE, "pdf content".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/cases/1/documents").file(doc))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_CLASSIFICATION"));
    }

    @Test
    void uploadDocuments_caseNotFound_returns404() throws Exception {
        when(caseService.addDocumentsAndReclassify(eq(999L), any(), isNull()))
                .thenThrow(new CaseNotFoundException(999L));

        MockMultipartFile doc = new MockMultipartFile(
                "police_report", "denuncia.pdf", MediaType.APPLICATION_PDF_VALUE, "pdf content".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/cases/999/documents").file(doc))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCase_withClassificationResult_returnsFullResponse() throws Exception {
        CaseResponse response = new CaseResponse(
                1L, null, null, CaseStatus.PENDING_ANALYST_REVIEW,
                "Celulares", "Celular Protegido Básico", "Robo en vía pública",
                "Motorola Edge 50 Pro", "40.123.456", "Laura Fernández", false, "POL-CEL-2024-001",
                "Me robaron el celular",
                LocalDateTime.of(2026, 6, 13, 19, 45), "CABA",
                new BigDecimal("150000"),
                Classification.FAST_TRACK, 1.0,
                List.of("Low amount", "first claim", "policy up to date"),
                null, null, null, null, null, null,
                Instant.parse("2026-06-13T22:50:00Z"),
                Instant.parse("2026-06-13T22:55:00Z"),
                LocalDate.of(2026, 7, 13), DeadlinePriority.NONE,
                List.of(
                        new StatusTransitionResponse(null, CaseStatus.PENDING_CLASSIFICATION,
                                StatusChangeActor.INSURED, "denuncia registrada",
                                Instant.parse("2026-06-13T22:50:00Z")),
                        new StatusTransitionResponse(CaseStatus.PENDING_CLASSIFICATION,
                                CaseStatus.PENDING_ANALYST_REVIEW,
                                StatusChangeActor.SYSTEM, "clasificación: FAST_TRACK",
                                Instant.parse("2026-06-13T22:55:00Z"))
                ),
                // Un adjunto con un campo leído (el importe) y otro que el documento no trae
                // (el IMEI): null viaja como null, que la pantalla muestra "no aplica" y NUNCA
                // como discrepancia.
                List.of(new DocumentAnalysisSummary(
                        "purchase_proof", "Factura de compra…",
                        LocalDate.of(2026, 5, 30), new BigDecimal("150000"),
                        "Motorola Edge 50 Pro", null, "TITULAR",
                        List.of("La tipografía del encabezado no coincide con el resto")))
        );
        when(caseService.getCase(1L, (String) null)).thenReturn(response);

        mockMvc.perform(get("/api/v1/cases/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisClassification").value("FAST_TRACK"))
                .andExpect(jsonPath("$.analysisConfidence").value(1.0))
                // Semáforo de vencimiento: fecha límite + prioridad derivada viajan en el read model.
                .andExpect(jsonPath("$.responseDeadline").value("2026-07-13"))
                .andExpect(jsonPath("$.deadlinePriority").value("NONE"))
                .andExpect(jsonPath("$.analysisReasons.length()").value(3))
                .andExpect(jsonPath("$.analysisReasons[0]").value("Low amount"))
                .andExpect(jsonPath("$.statusHistory.length()").value(2))
                .andExpect(jsonPath("$.statusHistory[0].toStatus").value("PENDING_CLASSIFICATION"))
                .andExpect(jsonPath("$.statusHistory[1].actor").value("SYSTEM"))
                .andExpect(jsonPath("$.statusHistory[1].changedAt").exists())
                .andExpect(jsonPath("$.documentAnalyses.length()").value(1))
                .andExpect(jsonPath("$.documentAnalyses[0].documentType").value("purchase_proof"))
                .andExpect(jsonPath("$.documentAnalyses[0].amount").value(150000))
                // El campo que el documento no dice viaja null, no ausente ni "" — es lo que le
                // permite al front distinguir "no aplica" de un valor que no coincide.
                .andExpect(jsonPath("$.documentAnalyses[0].imei").doesNotExist())
                .andExpect(jsonPath("$.documentAnalyses[0].visualFindings.length()").value(1));
    }

    @Test
    void getCase_classificationFailed_returnsFailedStatus() throws Exception {
        CaseResponse response = caseResponse(1L, CaseStatus.CLASSIFICATION_FAILED);
        when(caseService.getCase(1L, (String) null)).thenReturn(response);

        mockMvc.perform(get("/api/v1/cases/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLASSIFICATION_FAILED"));
    }

    @Test
    void getCase_awaitingDocumentation_returnsAwaitingStatus() throws Exception {
        CaseResponse response = caseResponse(3L, CaseStatus.AWAITING_DOCUMENTATION);
        when(caseService.getCase(3L, (String) null)).thenReturn(response);

        mockMvc.perform(get("/api/v1/cases/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_DOCUMENTATION"));
    }

    // ─── reopenCase ("rehabilitación", doc de dominio BBVA) ────────────────────────

    @Test
    void reopenCase_returns200WithTheReopenedCase() throws Exception {
        CaseResponse response = caseResponse(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseService.reopenCase(1L, "el analista se equivocó")).thenReturn(response);

        mockMvc.perform(post("/api/v1/cases/1/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"el analista se equivocó\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_ANALYST_REVIEW"));
    }

    /** {@code reason} es obligatorio: es la única explicación que queda en el historial. */
    @Test
    void reopenCase_blankReason_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/cases/1/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reopenCase_unknownCase_returns404() throws Exception {
        when(caseService.reopenCase(999L, "motivo")).thenThrow(new CaseNotFoundException(999L));

        mockMvc.perform(post("/api/v1/cases/999/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isNotFound());
    }

    /** Un expediente que sigue abierto no tiene nada que reabrir — la máquina de estados lo corta con 409. */
    @Test
    void reopenCase_caseStillOpen_returns409() throws Exception {
        when(caseService.reopenCase(1L, "motivo")).thenThrow(
                new InvalidStatusTransitionException(CaseStatus.PENDING_ANALYST_REVIEW, CaseStatus.PENDING_ANALYST_REVIEW));

        mockMvc.perform(post("/api/v1/cases/1/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"motivo\"}"))
                .andExpect(status().isConflict());
    }

    private CaseResponse caseResponse(Long id, CaseStatus status) {
        return new CaseResponse(
                id, null, null, status,
                "Celulares", "Celular Protegido Básico", "Robo en vía pública",
                "Motorola Edge 50 Pro", "40.123.456", "Laura Fernández", false, "POL-CEL-2024-001",
                "Me robaron el celular",
                LocalDateTime.of(2026, 6, 13, 19, 45), "CABA",
                new BigDecimal("150000"),
                null, 0.0, null,
                null, null, null, null, null, null,
                Instant.parse("2026-06-13T22:50:00Z"),
                Instant.parse("2026-06-13T22:50:00Z"),
                LocalDate.of(2026, 7, 13), DeadlinePriority.NONE,
                null,
                List.of()
        );
    }
}
