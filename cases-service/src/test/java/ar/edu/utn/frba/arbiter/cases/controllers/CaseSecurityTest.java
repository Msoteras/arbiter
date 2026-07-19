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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H0003 - RBAC por endpoint. Usa tokens firmados a mano con el mismo secreto de test
 * (ver {@link AbstractPersistenceIT}) porque cases-service solo valida el JWT, no lo emite.
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
                  "eventLocation": "CABA"
                }
                """.getBytes()
        );

        mockMvc.perform(multipart("/api/v1/cases").file(casePart)
                        .header("Authorization", "Bearer " + tokenFor("ANALISTA_SINIESTROS")))
                .andExpect(status().isForbidden());
    }

    @Test
    void recordDecision_asAsegurado_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/cases/1/decision")
                        .header("Authorization", "Bearer " + tokenFor("ASEGURADO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analystId": "a1", "decision": "APPROVE"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void recordDecision_asAnalista_passesTheRoleGate() throws Exception {
        // Caso inexistente a propósito: si pasa el @PreAuthorize, la lógica de negocio
        // responde 404 (CaseNotFoundException) en vez de 401/403.
        mockMvc.perform(post("/api/v1/cases/999999/decision")
                        .header("Authorization", "Bearer " + tokenFor("ANALISTA_SINIESTROS"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analystId": "a1", "decision": "APPROVE"}
                                """))
                .andExpect(status().isNotFound());
    }
}
