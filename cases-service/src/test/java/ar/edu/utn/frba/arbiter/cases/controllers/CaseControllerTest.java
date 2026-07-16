package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.dto.StatusTransitionResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseExceptionHandler;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.services.CaseService;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
                  "eventLocation": "CABA"
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
        when(caseService.getCase(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/cases/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("PENDING_ANALYST_REVIEW"));
    }

    @Test
    void getCase_notFound_returns404ProblemDetail() throws Exception {
        when(caseService.getCase(999L)).thenThrow(new CaseNotFoundException(999L));

        mockMvc.perform(get("/api/v1/cases/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Case 999 not found"));
    }

    @Test
    void listCases_noStatusFilter_returnsAllAsArray() throws Exception {
        CaseResponse case1 = caseResponse(2L, CaseStatus.PENDING_ANALYST_REVIEW);
        CaseResponse case2 = caseResponse(1L, CaseStatus.APPROVED);
        when(caseService.listCases(isNull(), isNull())).thenReturn(List.of(case1, case2));

        mockMvc.perform(get("/api/v1/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[1].id").value(1));
    }

    @Test
    void listCases_withStatusFilter_passesStatusThrough() throws Exception {
        CaseResponse response = caseResponse(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseService.listCases(eq(CaseStatus.PENDING_ANALYST_REVIEW), isNull())).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/cases").param("status", "PENDING_ANALYST_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING_ANALYST_REVIEW"));
    }

    @Test
    void listCases_withInsuredIdFilter_passesInsuredIdThrough() throws Exception {
        CaseResponse response = caseResponse(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseService.listCases(isNull(), eq("40.123.456"))).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/cases").param("insuredId", "40.123.456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].insuredId").value("40.123.456"));
    }

    @Test
    void listCases_noResults_returnsEmptyArray() throws Exception {
        when(caseService.listCases(isNull(), isNull())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void uploadDocuments_returns202() throws Exception {
        CaseResponse response = caseResponse(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseService.addDocumentsAndReclassify(eq(1L), any())).thenReturn(response);

        MockMultipartFile doc = new MockMultipartFile(
                "police_report", "denuncia.pdf", MediaType.APPLICATION_PDF_VALUE, "pdf content".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/cases/1/documents").file(doc))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_CLASSIFICATION"));
    }

    @Test
    void uploadDocuments_caseNotFound_returns404() throws Exception {
        when(caseService.addDocumentsAndReclassify(eq(999L), any()))
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
                1L, CaseStatus.PENDING_ANALYST_REVIEW,
                "Celulares", "Celular Protegido Básico", "Robo en vía pública",
                "Motorola Edge 50 Pro", "40.123.456", "POL-CEL-2024-001",
                "Me robaron el celular",
                LocalDateTime.of(2026, 6, 13, 19, 45), "CABA",
                new BigDecimal("150000"),
                Classification.FAST_TRACK, 1.0,
                "Low amount, first claim, policy up to date",
                null, null, null,
                Instant.parse("2026-06-13T22:50:00Z"),
                Instant.parse("2026-06-13T22:55:00Z"),
                List.of(
                        new StatusTransitionResponse(null, CaseStatus.PENDING_CLASSIFICATION,
                                StatusChangeActor.INSURED, "denuncia registrada",
                                Instant.parse("2026-06-13T22:50:00Z")),
                        new StatusTransitionResponse(CaseStatus.PENDING_CLASSIFICATION,
                                CaseStatus.PENDING_ANALYST_REVIEW,
                                StatusChangeActor.SYSTEM, "clasificación: FAST_TRACK",
                                Instant.parse("2026-06-13T22:55:00Z"))
                )
        );
        when(caseService.getCase(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/cases/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisClassification").value("FAST_TRACK"))
                .andExpect(jsonPath("$.analysisConfidence").value(1.0))
                .andExpect(jsonPath("$.analysisDetail").value("Low amount, first claim, policy up to date"))
                .andExpect(jsonPath("$.statusHistory.length()").value(2))
                .andExpect(jsonPath("$.statusHistory[0].toStatus").value("PENDING_CLASSIFICATION"))
                .andExpect(jsonPath("$.statusHistory[1].actor").value("SYSTEM"))
                .andExpect(jsonPath("$.statusHistory[1].changedAt").exists());
    }

    @Test
    void getCase_classificationFailed_returnsFailedStatus() throws Exception {
        CaseResponse response = caseResponse(1L, CaseStatus.CLASSIFICATION_FAILED);
        when(caseService.getCase(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/cases/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLASSIFICATION_FAILED"));
    }

    @Test
    void getCase_awaitingDocumentation_returnsAwaitingStatus() throws Exception {
        CaseResponse response = caseResponse(3L, CaseStatus.AWAITING_DOCUMENTATION);
        when(caseService.getCase(3L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/cases/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_DOCUMENTATION"));
    }

    private CaseResponse caseResponse(Long id, CaseStatus status) {
        return new CaseResponse(
                id, status,
                "Celulares", "Celular Protegido Básico", "Robo en vía pública",
                "Motorola Edge 50 Pro", "40.123.456", "POL-CEL-2024-001",
                "Me robaron el celular",
                LocalDateTime.of(2026, 6, 13, 19, 45), "CABA",
                new BigDecimal("150000"),
                null, 0.0, null,
                null, null, null,
                Instant.parse("2026-06-13T22:50:00Z"),
                Instant.parse("2026-06-13T22:50:00Z"),
                null
        );
    }
}
