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
        // Denunciar es del asegurado y de nadie más. El endpoint estaba en
        // hasAnyRole('ASEGURADO','REFERENTE_ASEGURADORA'), más laxo que la regla de negocio — el
        // frontend ya restringía la ruta `new-claim` a ASEGURADO.
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
        // Misma regla: la documentación adicional la sube el asegurado dueño del expediente (H0005).
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

    // Los bodies de acá abajo llevan `justification` porque es obligatoria (@NotBlank): la
    // validación del @RequestBody corre ANTES que el @PreAuthorize, así que un body incompleto
    // devuelve 400 y estos tests dejarían de medir el gate de seguridad, que es lo suyo.

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
     * La contracara: la justificación es obligatoria para toda decisión, coincida o no con el
     * modelo (el paper §2.2 exige que cada decisión quede explícita y fundada). Sin este test, que
     * volviera a ser opcional no rompería nada.
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
        // Caso inexistente a propósito: si pasa el @PreAuthorize, la lógica de negocio
        // responde 404 (CaseNotFoundException) en vez de 401/403.
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
        // Asignar es de los dos roles operativos, no solo del referente: el analista puede tomar
        // un expediente sin depender de que se lo repartan. Mismo truco del 404 que en decision.
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
     * Para el asegurado la derivación no existe: su expediente sigue 'En análisis'. Leer el
     * peritaje le contaría que se sospecha de él, que es justo lo que el estado esconde.
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
