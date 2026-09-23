package ar.edu.utn.frba.arbiter.classification.controllers;

import ar.edu.utn.frba.arbiter.classification.models.repositories.CaseOutcomeRepository;
import ar.edu.utn.frba.arbiter.classification.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Per-endpoint RBAC. Tokens are signed by hand with the test secret: this module only validates them. */
@SpringBootTest
@AutoConfigureMockMvc
class ClaimSecurityTest extends AbstractPersistenceIT {

    private static final String SECRET = "test-secret-at-least-32-bytes-long-for-hs256";

    @Autowired
    private MockMvc mockMvc;

    /** Mocked because {@code cases} doesn't exist in this module's test container. */
    @MockitoBean
    private CaseOutcomeRepository caseOutcomeRepository;

    @BeforeEach
    void stubCaseLookup() {
        when(caseOutcomeRepository.findOutcome(any()))
                .thenReturn(new CaseOutcomeRepository.CaseOutcome(false, null, null));
    }

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

    private MockMultipartFile claimPart() {
        return new MockMultipartFile(
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
                """.getBytes()
        );
    }

    @Test
    void classify_withoutToken_returns401() throws Exception {
        mockMvc.perform(multipart("/api/v1/claims").file(claimPart()).param("caseId", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void classify_anyAuthenticatedRole_returns202() throws Exception {
        mockMvc.perform(multipart("/api/v1/claims").file(claimPart()).param("caseId", "1")
                        .header("Authorization", "Bearer " + tokenFor("ASEGURADO")))
                .andExpect(status().isAccepted());
    }

    @Test
    void getStatus_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/claims/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getStatus_anyAuthenticatedRole_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/claims/1")
                        .header("Authorization", "Bearer " + tokenFor("ASEGURADO")))
                .andExpect(status().isOk());
    }

    // Bodies must be valid: @RequestBody validation runs before @PreAuthorize, so an invalid body
    // would return 400 and the security gate wouldn't be measured.

    @Test
    void recordDecision_asAsegurado_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/claims/1/decision")
                        .header("Authorization", "Bearer " + tokenFor("ASEGURADO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analystId": 1, "decision": "APPROVE",
                                 "justification": "Documentación completa"}
                                """))
                .andExpect(status().isForbidden());
    }

    /** The analystId travels in the body, so only cases-service's service token may call this. */
    @Test
    void recordDecision_asAnalista_isRejected() throws Exception {
        mockMvc.perform(post("/api/v1/claims/999999/decision")
                        .header("Authorization", "Bearer " + tokenFor("ANALISTA_SINIESTROS"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analystId": 1, "decision": "APPROVE",
                                 "justification": "Documentación completa"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void recordDecision_withServiceToken_passesTheGate() throws Exception {
        // Case 999999 doesn't exist: past the gate, the logic answers 422 rather than 401/403.
        mockMvc.perform(post("/api/v1/claims/999999/decision")
                        .header("Authorization", "Bearer " + serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analystId": 1, "decision": "APPROVE",
                                 "justification": "Documentación completa"}
                                """))
                .andExpect(status().isUnprocessableContent());
    }

    private String serviceToken() {
        return JwtSupport.issueServiceToken(JwtSupport.key(SECRET), "cases-service-decision");
    }
}
