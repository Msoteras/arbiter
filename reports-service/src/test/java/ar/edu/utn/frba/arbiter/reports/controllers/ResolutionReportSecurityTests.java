package ar.edu.utn.frba.arbiter.reports.controllers;

import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import ar.edu.utn.frba.arbiter.reports.dto.ExportedReport;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.exceptions.InvalidReportPeriodException;
import ar.edu.utn.frba.arbiter.reports.services.ResolutionReportService;
import ar.edu.utn.frba.arbiter.reports.support.AbstractPersistenceIT;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.approvedRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.augustReport;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC and the HTTP contract of the report endpoints. The service is mocked: what the report
 * contains is {@code ResolvedCaseRepositoryTests}' job, this one is about who gets it and in what
 * shape. Tokens are signed by hand with the test secret, same as cases-service's
 * {@code CaseSecurityTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResolutionReportSecurityTests extends AbstractPersistenceIT {

    private static final String SECRET = "test-secret-at-least-32-bytes-long-for-hs256";
    private static final String PREVIEW = "/api/v1/reports/resolutions";
    private static final String EXPORT = "/api/v1/reports/resolutions/export";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResolutionReportService resolutionReportService;

    private String bearer(String rol) {
        Instant now = Instant.now();
        return "Bearer " + Jwts.builder()
                .subject("test@arbiter.test")
                .claim("rol", rol)
                .claim("tenantSchema", "arbiter_bbva")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(5))))
                .signWith(JwtSupport.key(SECRET))
                .compact();
    }

    @Test
    void preview_withoutToken_returns401() throws Exception {
        mockMvc.perform(get(PREVIEW).param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void preview_asInsured_returns403() throws Exception {
        mockMvc.perform(get(PREVIEW).param("from", "2026-08-01").param("to", "2026-08-31")
                        .header("Authorization", bearer("ASEGURADO")))
                .andExpect(status().isForbidden());
    }

    @Test
    void preview_asReferent_returnsTheRows() throws Exception {
        given(resolutionReportService.generate(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), "Hurto"))
                .willReturn(augustReport(List.of(approvedRow(42))));

        mockMvc.perform(get(PREVIEW).param("from", "2026-08-01").param("to", "2026-08-31")
                        .param("claimCause", "Hurto")
                        .header("Authorization", bearer("REFERENTE_ASEGURADORA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-08-01"))
                .andExpect(jsonPath("$.rows[0].caseId").value(42))
                .andExpect(jsonPath("$.rows[0].finalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.rows[0].classification").value("LLM_RECOMIENDA_APROBAR"));
    }

    @Test
    void preview_asAnalyst_returns200() throws Exception {
        given(resolutionReportService.generate(any(), any(), any())).willReturn(augustReport(List.of()));

        mockMvc.perform(get(PREVIEW).param("from", "2026-08-01").param("to", "2026-08-31")
                        .header("Authorization", bearer("ANALISTA_SINIESTROS")))
                .andExpect(status().isOk());
    }

    @Test
    void preview_withoutFrom_returns400() throws Exception {
        mockMvc.perform(get(PREVIEW).param("to", "2026-08-31")
                        .header("Authorization", bearer("REFERENTE_ASEGURADORA")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void preview_invalidPeriod_returnsProblemDetail400() throws Exception {
        given(resolutionReportService.generate(any(), any(), any()))
                .willThrow(new InvalidReportPeriodException("'from' is after 'to'"));

        mockMvc.perform(get(PREVIEW).param("from", "2026-09-01").param("to", "2026-08-01")
                        .header("Authorization", bearer("REFERENTE_ASEGURADORA")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("'from' is after 'to'"));
    }

    @Test
    void export_returnsTheFileAsAnAttachment() throws Exception {
        byte[] csv = "Nº expediente;Asegurado\r\n".getBytes(StandardCharsets.UTF_8);
        given(resolutionReportService.export(any(), any(), any(), eq(ReportFormat.CSV)))
                .willReturn(new ExportedReport("resoluciones_2026-08-01_2026-08-31.csv", ReportFormat.CSV, csv));

        mockMvc.perform(get(EXPORT).param("from", "2026-08-01").param("to", "2026-08-31").param("format", "CSV")
                        .header("Authorization", bearer("REFERENTE_ASEGURADORA")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"resoluciones_2026-08-01_2026-08-31.csv\""))
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(content().bytes(csv));
    }

    @Test
    void export_asInsured_returns403() throws Exception {
        mockMvc.perform(get(EXPORT).param("from", "2026-08-01").param("to", "2026-08-31").param("format", "PDF")
                        .header("Authorization", bearer("ASEGURADO")))
                .andExpect(status().isForbidden());
    }

    @Test
    void export_unknownFormat_returns400() throws Exception {
        mockMvc.perform(get(EXPORT).param("from", "2026-08-01").param("to", "2026-08-31").param("format", "DOCX")
                        .header("Authorization", bearer("REFERENTE_ASEGURADORA")))
                .andExpect(status().isBadRequest());
    }
}
