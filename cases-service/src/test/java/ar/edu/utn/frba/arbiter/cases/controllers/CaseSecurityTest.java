package ar.edu.utn.frba.arbiter.cases.controllers;

import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC per endpoint. Uses hand-signed tokens with the shared test secret (see
 * {@link AbstractPersistenceIT}) because cases-service only validates the JWT, it doesn't issue it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaseSecurityTest extends AbstractPersistenceIT {

    private static final String SECRET = "test-secret-at-least-32-bytes-long-for-hs256";

    @Autowired
    private MockMvc mockMvc;

    private String tokenFor(String rol) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("test@arbiter.test")
                .claim("rol", rol)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(5))))
                .signWith(JwtSupport.key(SECRET))
                .compact();
    }

    @Test
    void listCases_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/cases"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listCases_anyAuthenticatedRole_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/cases").header("Authorization", "Bearer " + tokenFor("ASEGURADO")))
                .andExpect(status().isOk());
    }

    @Test
    void createCase_asAnalista_returns403() throws Exception {
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

        mockMvc.perform(multipart("/api/v1/cases").file(casePart)
                        .header("Authorization", "Bearer " + tokenFor("ANALISTA_SINIESTROS")))
                .andExpect(status().isForbidden());
    }

    @Test
    void createCase_asReferente_returns403() throws Exception {
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

        mockMvc.perform(multipart("/api/v1/cases").file(casePart)
                        .header("Authorization", "Bearer " + tokenFor("REFERENTE_ASEGURADORA")))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadDocuments_asReferente_returns403() throws Exception {
        MockMultipartFile doc = new MockMultipartFile(
                "police_report", "denuncia.pdf", MediaType.APPLICATION_PDF_VALUE, "contenido".getBytes());

        mockMvc.perform(multipart("/api/v1/cases/1/documents").file(doc)
                        .header("Authorization", "Bearer " + tokenFor("REFERENTE_ASEGURADORA")))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadDocuments_asAnalista_returns403() throws Exception {
        MockMultipartFile doc = new MockMultipartFile(
                "police_report", "denuncia.pdf", MediaType.APPLICATION_PDF_VALUE, "contenido".getBytes());

        mockMvc.perform(multipart("/api/v1/cases/1/documents").file(doc)
                        .header("Authorization", "Bearer " + tokenFor("ANALISTA_SINIESTROS")))
                .andExpect(status().isForbidden());
    }

    // The bodies below carry `justification` because it's @NotBlank: @RequestBody validation runs
    // BEFORE @PreAuthorize, so an incomplete body would return 400 and these tests would stop
    // measuring the security gate.

    @Test
    void recordDecision_asAsegurado_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/cases/1/decision")
                        .header("Authorization", "Bearer " + tokenFor("ASEGURADO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analystId": 1, "decision": "APPROVE",
                                 "justification": "Documentación completa"}
                                """))
                .andExpect(status().isForbidden());
    }

    /**
     * Justification is mandatory for every decision, whether or not it matches the model. Without
     * this test, making it optional again would break nothing.
     */
    @Test
    void recordDecision_withoutJustification_isRejected() throws Exception {
        mockMvc.perform(post("/api/v1/cases/999999/decision")
                        .header("Authorization", "Bearer " + tokenFor("ANALISTA_SINIESTROS"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analystId": 1, "decision": "APPROVE"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordDecision_asAnalista_passesTheRoleGate() throws Exception {
        // Non-existent case on purpose: once past @PreAuthorize, the business logic answers 404
        // (CaseNotFoundException) instead of 401/403.
        mockMvc.perform(post("/api/v1/cases/999999/decision")
                        .header("Authorization", "Bearer " + tokenFor("ANALISTA_SINIESTROS"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analystId": 1, "decision": "APPROVE",
                                 "justification": "Documentación completa"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void assignAnalyst_asAsegurado_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/cases/1/assign")
                        .header("Authorization", "Bearer " + tokenFor("ASEGURADO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analystId": 2}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void assignAnalyst_asAnalista_passesTheRoleGate() throws Exception {
        // Both operational roles may assign: an analyst can take a case without waiting to be given
        // one. Same 404 trick as in decision.
        mockMvc.perform(post("/api/v1/cases/999999/assign")
                        .header("Authorization", "Bearer " + tokenFor("ANALISTA_SINIESTROS"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analystId": 2}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void assignAnalyst_asReferente_passesTheRoleGate() throws Exception {
        mockMvc.perform(post("/api/v1/cases/999999/assign")
                        .header("Authorization", "Bearer " + tokenFor("REFERENTE_ASEGURADORA"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analystId": 2}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void unassignAnalyst_asAsegurado_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/cases/1/assign")
                        .header("Authorization", "Bearer " + tokenFor("ASEGURADO")))
                .andExpect(status().isForbidden());
    }

    /**
     * For the insured the derivation doesn't exist: their case still reads as under analysis.
     * Reading the expert assessment would reveal they're suspected, which the status hides.
     */
    @Test
    void readExpertAssessment_asAsegurado_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/cases/1/expert-assessment")
                        .header("Authorization", "Bearer " + tokenFor("ASEGURADO")))
                .andExpect(status().isForbidden());
    }

    @Test
    void deriveToExpert_asAsegurado_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/cases/1/expert-assessment")
                        .header("Authorization", "Bearer " + tokenFor("ASEGURADO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expertFirmId": 1, "reason": "sospecha"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void deriveToExpert_asAnalista_passesTheRoleGate() throws Exception {
        mockMvc.perform(post("/api/v1/cases/999999/expert-assessment")
                        .header("Authorization", "Bearer " + tokenFor("ANALISTA_SINIESTROS"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expertFirmId": 1, "reason": "banda crítica"}
                                """))
                .andExpect(status().isNotFound());
    }
}
