package ar.edu.utn.frba.arbiter.classification.controllers;

import ar.edu.utn.frba.arbiter.classification.exceptions.GlobalExceptionHandler;
import ar.edu.utn.frba.arbiter.classification.services.ClaimClassificationService;
import ar.edu.utn.frba.arbiter.classification.services.ClassificationResultsService;
import ar.edu.utn.frba.arbiter.classification.services.MultipartDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Response shapes: cases-service reads {@code classificationId} from the decision body by name. */
@WebMvcTest(ClaimController.class)
@Import(GlobalExceptionHandler.class)
class ClaimControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClaimClassificationService claimClassificationService;

    @MockitoBean
    private ClassificationResultsService resultsService;

    @MockitoBean
    private MultipartDocumentMapper documentMapper;

    @Test
    void classify_returns202WithPollingHints() throws Exception {
        when(documentMapper.toAttachmentDocuments(any(), any())).thenReturn(List.of());
        MockMultipartFile claim = new MockMultipartFile(
                "claim", "", MediaType.APPLICATION_JSON_VALUE,
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
                """.getBytes());

        mockMvc.perform(multipart("/api/v1/claims").file(claim).param("caseId", "5"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/claims/5"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.caseId").value(5))
                .andExpect(jsonPath("$.message").value("Analysis in progress."))
                .andExpect(jsonPath("$.checkResultAt").value("/api/v1/claims/5"));
    }

    @Test
    void recordDecision_returnsClassificationId() throws Exception {
        when(resultsService.recordAnalystDecision(eq(5L), any())).thenReturn(42L);

        mockMvc.perform(post("/api/v1/claims/5/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analystId": 1, "decision": "APPROVE",
                                 "justification": "Documentación completa"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.caseId").value(5))
                .andExpect(jsonPath("$.status").value("decision-recorded"))
                .andExpect(jsonPath("$.classificationId").value(42));
    }
}
