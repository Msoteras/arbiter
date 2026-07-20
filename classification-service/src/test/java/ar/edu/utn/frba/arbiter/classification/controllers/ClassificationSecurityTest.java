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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H0003 - el endpoint de testing aislado (sin caso/expediente detrás) queda restringido al
 * "módulo de scoring" del analista/referente, no accesible por el asegurado ni por callers
 * sin token.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClassificationSecurityTest extends AbstractPersistenceIT {

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
        mockMvc.perform(multipart("/api/v1/classifications").file(claimPart()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void classify_asAsegurado_returns403() throws Exception {
        mockMvc.perform(multipart("/api/v1/classifications").file(claimPart())
                        .header("Authorization", "Bearer " + tokenFor("ASEGURADO")))
                .andExpect(status().isForbidden());
    }

    @Test
    void classify_asAnalista_returns202() throws Exception {
        mockMvc.perform(multipart("/api/v1/classifications").file(claimPart())
                        .header("Authorization", "Bearer " + tokenFor("ANALISTA_SINIESTROS")))
                .andExpect(status().isAccepted());
    }

    @Test
    void getResults_asAsegurado_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/classifications/results")
                        .header("Authorization", "Bearer " + tokenFor("ASEGURADO")))
                .andExpect(status().isForbidden());
    }

    @Test
    void getResults_asReferente_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/classifications/results")
                        .header("Authorization", "Bearer " + tokenFor("REFERENTE_ASEGURADORA")))
                .andExpect(status().isOk());
    }
}
