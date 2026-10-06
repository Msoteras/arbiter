package ar.edu.utn.frba.arbiter.reports.controllers;

import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ClaimMetrics;
import ar.edu.utn.frba.arbiter.reports.dto.ComparisonMode;
import ar.edu.utn.frba.arbiter.reports.dto.ComparisonRequest;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsRecalculation;
import ar.edu.utn.frba.arbiter.reports.dto.ReportComparison;
import ar.edu.utn.frba.arbiter.reports.dto.DerivationTurnaround;
import ar.edu.utn.frba.arbiter.reports.dto.FastTrackImpact;
import ar.edu.utn.frba.arbiter.reports.dto.FraudDetection;
import ar.edu.utn.frba.arbiter.reports.dto.IntakeFunnel;
import ar.edu.utn.frba.arbiter.reports.dto.LegalDeadline;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsRange;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsSummary;
import ar.edu.utn.frba.arbiter.reports.dto.RecommendationAgreement;
import ar.edu.utn.frba.arbiter.reports.dto.ReopeningRate;
import ar.edu.utn.frba.arbiter.reports.dto.SettledAmounts;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionTarget;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.dto.TimelinePoint;
import ar.edu.utn.frba.arbiter.reports.exceptions.InvalidReportPeriodException;
import ar.edu.utn.frba.arbiter.reports.exceptions.TenantNotResolvedException;
import ar.edu.utn.frba.arbiter.reports.services.ClaimMetricsService;
import ar.edu.utn.frba.arbiter.reports.services.DailyMetricsService;
import ar.edu.utn.frba.arbiter.reports.support.AbstractPersistenceIT;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract, RBAC and isolation between insurers; the service is mocked. Tokens are signed by
 * hand with the test secret.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClaimMetricsSecurityTests extends AbstractPersistenceIT {

    private static final String SECRET = "test-secret-at-least-32-bytes-long-for-hs256";
    private static final String METRICS = "/api/v1/reports/metrics";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClaimMetricsService claimMetricsService;

    @MockitoBean
    private DailyMetricsService dailyMetricsService;

    @Test
    void withoutToken_returns401() throws Exception {
        mockMvc.perform(get(METRICS)).andExpect(status().isUnauthorized());
    }

    @Test
    void asInsured_returns403() throws Exception {
        mockMvc.perform(get(METRICS).header("Authorization", bearer("ASEGURADO", "arbiter_bbva")))
                .andExpect(status().isForbidden());
    }

    @Test
    void asReferent_returnsTheDashboard() throws Exception {
        given(claimMetricsService.generate(any(), any(), any(), any(), any())).willReturn(augustMetrics());

        mockMvc.perform(get(METRICS).param("from", "2026-08-01").param("to", "2026-08-31")
                        .header("Authorization", bearer("REFERENTE_ASEGURADORA", "arbiter_bbva")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-08-01"))
                .andExpect(jsonPath("$.granularity").value("DAY"))
                .andExpect(jsonPath("$.summary.reportedCases").value(40))
                .andExpect(jsonPath("$.summary.approvalRate").value(0.75))
                .andExpect(jsonPath("$.summary.averageWaitingHours").value(12.0))
                .andExpect(jsonPath("$.funnel.reported").value(40))
                .andExpect(jsonPath("$.funnel.analyzed").value(30))
                .andExpect(jsonPath("$.comparisonSummary.reportedCases").value(32))
                .andExpect(jsonPath("$.agreement.rate").value(6d / 7))
                .andExpect(jsonPath("$.resolutionTarget.targetDays").value(21))
                .andExpect(jsonPath("$.resolutionTarget.exceeded").value(3))
                .andExpect(jsonPath("$.byStatus[0].label").value("APPROVED"))
                .andExpect(jsonPath("$.timeline[0].reported").value(3));
    }

    @Test
    void theAnalystReadsItToo() throws Exception {
        given(claimMetricsService.generate(any(), any(), any(), any(), any())).willReturn(augustMetrics());

        mockMvc.perform(get(METRICS).param("range", "WEEK")
                        .header("Authorization", bearer("ANALISTA_SINIESTROS", "arbiter_bbva")))
                .andExpect(status().isOk());
    }

    @Test
    void theComparison_travelsToTheServiceAsAsked() throws Exception {
        given(claimMetricsService.generate(any(), any(), any(), any(), any())).willReturn(augustMetrics());

        mockMvc.perform(get(METRICS).param("range", "QUARTER")
                        .param("compare", "CUSTOM")
                        .param("compareFrom", "2025-06-01").param("compareTo", "2025-08-31")
                        .header("Authorization", bearer("REFERENTE_ASEGURADORA", "arbiter_bbva")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparison.mode").value("PREVIOUS_PERIOD"))
                .andExpect(jsonPath("$.comparison.from").value("2026-07-01"));

        verify(claimMetricsService).generate(MetricsRange.QUARTER, null, null, MetricsFilter.NONE,
                new ComparisonRequest(ComparisonMode.CUSTOM, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 8, 31)));
    }

    @Test
    void anUnknownComparison_returns400() throws Exception {
        mockMvc.perform(get(METRICS).param("compare", "LAST_DECADE")
                        .header("Authorization", bearer("REFERENTE_ASEGURADORA", "arbiter_bbva")))
                .andExpect(status().isBadRequest());
    }

    /** Recalculating rewrites what every reader of the dashboard sees, so reading it is not enough. */
    @Test
    void onlyTheReferent_recalculatesStoredDays() throws Exception {
        given(dailyMetricsService.recalculate(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .willReturn(new MetricsRecalculation(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 31));

        mockMvc.perform(post(METRICS + "/recalculations").param("from", "2026-08-01").param("to", "2026-08-31")
                        .header("Authorization", bearer("ANALISTA_SINIESTROS", "arbiter_bbva")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(METRICS + "/recalculations").param("from", "2026-08-01").param("to", "2026-08-31")
                        .header("Authorization", bearer("REFERENTE_ASEGURADORA", "arbiter_bbva")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(31));
    }

    /**
     * The insurer is never a parameter: the query runs against the schema in the caller's own token, so
     * referents of different companies reading the same URL read different data.
     */
    @Test
    void eachReferentReadsTheirOwnInsurersSchema() throws Exception {
        AtomicReference<String> schemaSeen = new AtomicReference<>();
        given(claimMetricsService.generate(any(), any(), any(), any(), any())).willAnswer(invocation -> {
            schemaSeen.set(TenantContext.get());
            return augustMetrics();
        });

        mockMvc.perform(get(METRICS).header("Authorization", bearer("REFERENTE_ASEGURADORA", "arbiter_bbva")))
                .andExpect(status().isOk());
        assertThat(schemaSeen.get()).isEqualTo("arbiter_bbva");

        mockMvc.perform(get(METRICS).header("Authorization", bearer("REFERENTE_ASEGURADORA", "arbiter_provincia")))
                .andExpect(status().isOk());
        assertThat(schemaSeen.get()).isEqualTo("arbiter_provincia");
    }

    @Test
    void aSessionWithNoInsurer_returns403() throws Exception {
        willThrow(new TenantNotResolvedException()).given(claimMetricsService).generate(any(), any(), any(), any(), any());

        mockMvc.perform(get(METRICS).header("Authorization", bearer("REFERENTE_ASEGURADORA", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anImpossiblePeriod_returns400AsProblemDetail() throws Exception {
        willThrow(new InvalidReportPeriodException("'from' is after 'to'"))
                .given(claimMetricsService).generate(any(), any(), any(), any(), any());

        mockMvc.perform(get(METRICS).param("from", "2026-08-31").param("to", "2026-08-01")
                        .header("Authorization", bearer("REFERENTE_ASEGURADORA", "arbiter_bbva")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'from' is after 'to'"));
    }

    @Test
    void anUnknownRange_returns400() throws Exception {
        mockMvc.perform(get(METRICS).param("range", "FORTNIGHT")
                        .header("Authorization", bearer("REFERENTE_ASEGURADORA", "arbiter_bbva")))
                .andExpect(status().isBadRequest());
    }

    private static ClaimMetrics augustMetrics() {
        return new ClaimMetrics(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                Instant.parse("2026-09-11T15:00:00Z"),
                TimelineGranularity.DAY,
                MetricsFilter.NONE,
                new IntakeFunnel(40, 30, 8, 10, 32),
                new MetricsSummary(40, 10, 8, 6, 2, 0, 0.75, 0.25, 0.25, 36.5, 12.0),
                new ReportComparison(ComparisonMode.PREVIOUS_PERIOD,
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)),
                new MetricsSummary(32, 8, 6, 5, 1, 0, 0.83, 0.17, 0.25, 40.0, null),
                RecommendationAgreement.of(7, 6),
                new ResolutionTarget(true, 21, 3),
                LegalDeadline.of(8, 7),
                ReopeningRate.of(8, 1),
                SettledAmounts.of(6, new BigDecimal("1830000.00"), new BigDecimal("2100000.00"), 6,
                        new BigDecimal("210000.00"), BigDecimal.ZERO, new BigDecimal("60000.00")),
                new FraudDetection(8, 1, 1, new BigDecimal("340000.00")),
                new FastTrackImpact(2, 48.0, 6, 840.0),
                List.of(new DerivationTurnaround("ESTUDIO_LIQUIDADOR", 3, 2, 120.0)),
                List.of(new MetricCount("APPROVED", 6), new MetricCount("REJECTED", 2)),
                List.of(new MetricCount("Celulares", 40)),
                List.of(new MetricCount("LLM_RECOMIENDA_APROBAR", 30)),
                List.of(new MetricCount("HIGH", 4)),
                List.of(new MetricCount("Vigencia de la póliza", 2)),
                List.of(new TimelinePoint(LocalDate.of(2026, 8, 1), 3, 1)));
    }

    private String bearer(String rol, String tenantSchema) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject("test@arbiter.test")
                .claim("rol", rol)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(5))));
        if (tenantSchema != null) {
            builder.claim("tenantSchema", tenantSchema);
        }
        return "Bearer " + builder.signWith(JwtSupport.key(SECRET)).compact();
    }
}
