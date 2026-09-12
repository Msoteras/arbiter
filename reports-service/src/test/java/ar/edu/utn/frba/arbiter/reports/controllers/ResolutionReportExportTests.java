package ar.edu.utn.frba.arbiter.reports.controllers;

import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import ar.edu.utn.frba.arbiter.reports.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.reports.support.CaseTables;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.APPROVED;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.LAURA;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.PENDING_REVIEW;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.ROBO_CELULARES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end through HTTP with the real service, repository and exporters over a seeded database —
 * no mocks. {@code ResolutionReportSecurityTests} covers who may call these endpoints; this one
 * covers that what comes back is the actual report: the bytes a referent downloads, built from rows
 * that are really in the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResolutionReportExportTests extends AbstractPersistenceIT {

    private static final String SECRET = "test-secret-at-least-32-bytes-long-for-hs256";
    private static final String PREVIEW = "/api/v1/reports/resolutions";
    private static final String EXPORT = "/api/v1/reports/resolutions/export";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private CaseTables tables;

    @BeforeAll
    void createTables() {
        tables = new CaseTables(jdbcTemplate);
        tables.create();
    }

    /** One case approved on 03/08/2026, the same one every test below reads. */
    @BeforeEach
    void seedResolvedCase() {
        tables.reset();
        tables.decision(1, "APPROVE", LAURA);
        tables.insertCase(1, "2026-08-01T10:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, 1L);
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-03T12:30:00Z");
        tables.recommendation(1, "LLM_RECOMIENDA_APROBAR");
    }

    /**
     * The tenant schema travels in the token. Here it's {@code public}, which is where
     * {@link AbstractPersistenceIT}'s flat test schema keeps everything.
     */
    private String bearer(String rol, String tenantSchema) {
        Instant now = Instant.now();
        var token = Jwts.builder()
                .subject("referente@arbiter.test")
                .claim("rol", rol)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(5))));
        if (tenantSchema != null) {
            token.claim("tenantSchema", tenantSchema);
        }
        return "Bearer " + token.signWith(JwtSupport.key(SECRET)).compact();
    }

    @Test
    void preview_returnsTheCaseThatWasResolvedInThePeriod() throws Exception {
        mockMvc.perform(get(PREVIEW).param("from", "2026-08-01").param("to", "2026-08-31")
                        .header("Authorization", bearer("REFERENTE_ASEGURADORA", "public")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].caseId").value(1))
                .andExpect(jsonPath("$.rows[0].insuredName").value("Ana Pérez"))
                .andExpect(jsonPath("$.rows[0].totalMinutes").value(2 * 24 * 60 + 150))
                .andExpect(jsonPath("$.rows[0].classification").value("LLM_RECOMIENDA_APROBAR"))
                .andExpect(jsonPath("$.rows[0].analystDecision").value("APPROVE"))
                .andExpect(jsonPath("$.rows[0].finalStatus").value("APPROVED"));
    }

    @Test
    void csvExport_downloadsTheRealFile() throws Exception {
        MvcResult result = mockMvc.perform(get(EXPORT)
                        .param("from", "2026-08-01").param("to", "2026-08-31").param("format", "CSV")
                        .header("Authorization", bearer("REFERENTE_ASEGURADORA", "public")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"resoluciones_2026-08-01_2026-08-31.csv\""))
                .andReturn();

        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv.charAt(0)).isEqualTo((char) 0xFEFF);
        assertThat(csv).contains("Nº expediente;Asegurado;DNI");
        // Labels, not enum literals: nobody translates this file downstream.
        assertThat(csv).contains("Ana Pérez;30.111.222;Celulares;Robo en vía pública")
                .contains("Recomienda aprobar;Aprobó;Aprobado;Laura Gómez");
    }

    @Test
    void pdfExport_downloadsARealPdf() throws Exception {
        MvcResult result = mockMvc.perform(get(EXPORT)
                        .param("from", "2026-08-01").param("to", "2026-08-31").param("format", "PDF")
                        .header("Authorization", bearer("REFERENTE_ASEGURADORA", "public")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"resoluciones_2026-08-01_2026-08-31.pdf\""))
                .andReturn();

        byte[] pdf = result.getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void aTokenWithoutAnInsurer_isRejectedInsteadOfAnsweringAnEmptyReport() throws Exception {
        mockMvc.perform(get(PREVIEW).param("from", "2026-08-01").param("to", "2026-08-31")
                        .header("Authorization", bearer("REFERENTE_ASEGURADORA", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }
}
