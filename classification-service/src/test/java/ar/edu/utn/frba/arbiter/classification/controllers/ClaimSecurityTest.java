package ar.edu.utn.frba.arbiter.classification.controllers;

import ar.edu.utn.frba.arbiter.classification.support.AbstractPersistenceIT;
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
 * H0003 - RBAC por endpoint. classification-service era el único módulo sin ningún control de
 * acceso (sin @PreAuthorize, sin SecurityConfig, alcanzable sin JWT desde cualquier caller con
 * acceso de red al puerto 8082); esto lo cierra. Tokens firmados a mano con el mismo secreto de
 * test (ver {@link AbstractPersistenceIT}) porque este módulo solo valida el JWT, no lo emite.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClaimSecurityTest extends AbstractPersistenceIT {

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

    @Test
    void recordDecision_asAsegurado_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/claims/1/decision")
                        .header("Authorization", "Bearer " + tokenFor("ASEGURADO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analystId": "a1", "decision": "APPROVE"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void recordDecision_asAnalista_passesTheRoleGate() throws Exception {
        // Sin clasificación previa para el caso 999999 a propósito: si pasa el @PreAuthorize,
        // la lógica de negocio responde 422 (InvalidClassificationException) en vez de 401/403.
        mockMvc.perform(post("/api/v1/claims/999999/decision")
                        .header("Authorization", "Bearer " + tokenFor("ANALISTA_SINIESTROS"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analystId": "a1", "decision": "APPROVE"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }
}
