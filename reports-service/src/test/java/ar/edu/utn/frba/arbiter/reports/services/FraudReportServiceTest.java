package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ExportedReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSignal;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSummary;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.exceptions.InvalidReportPeriodException;
import ar.edu.utn.frba.arbiter.reports.exceptions.TenantNotResolvedException;
import ar.edu.utn.frba.arbiter.reports.exceptions.UnknownBranchException;
import ar.edu.utn.frba.arbiter.reports.models.repositories.FlaggedCaseRepository;
import ar.edu.utn.frba.arbiter.reports.services.export.FraudReportExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class FraudReportServiceTest {

    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);

    @Mock
    private FlaggedCaseRepository repository;

    @Mock
    private FraudReportExporter csvExporter;

    @Mock
    private FraudReportExporter pdfExporter;

    private FraudReportService service;

    @BeforeEach
    void setUp() {
        service = new FraudReportService(repository, List.of(csvExporter, pdfExporter), CLOCK);
        TenantContext.set("arbiter_bbva");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void withoutATenant_refusesToReportAnything() {
        TenantContext.clear();

        assertThatThrownBy(() -> service.generate(SEP_1, SEP_30, null, null))
                .isInstanceOf(TenantNotResolvedException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void anInvertedOrOverlongPeriod_isRejectedBeforeQuerying() {
        assertThatThrownBy(() -> service.generate(SEP_30, SEP_1, null, null))
                .isInstanceOf(InvalidReportPeriodException.class);
        assertThatThrownBy(() -> service.generate(SEP_1, SEP_1.plusYears(2), null, null))
                .isInstanceOf(InvalidReportPeriodException.class);
        verifyNoInteractions(repository);
    }

    /** Whole calendar days, both ends included: "hasta el 30/09" reaches the next midnight. */
    @Test
    void thePeriod_coversBothDaysWhole() {
        service.generate(SEP_1, SEP_30, null, null);

        verify(repository).findFlaggedBetween(
                eq(Instant.parse("2026-09-01T03:00:00Z")),
                eq(Instant.parse("2026-10-01T03:00:00Z")),
                isNull(), isNull());
    }

    @Test
    void theFilters_goDown_andTheBranchNameComesBack() {
        given(repository.findBranchName(7L)).willReturn("Celulares");

        FraudReport report = service.generate(SEP_1, SEP_30, 7L, RiskBand.CRITICAL);

        // The exact period, not just(any(), any()): that wildcard also matches the previous-period
        // query generate() now makes, and a loose verify here can't tell the two calls apart.
        verify(repository).findFlaggedBetween(
                eq(Instant.parse("2026-09-01T03:00:00Z")), eq(Instant.parse("2026-10-01T03:00:00Z")),
                eq(7L), eq(RiskBand.CRITICAL));
        assertThat(report.branch()).isEqualTo("Celulares");
        assertThat(report.riskBand()).isEqualTo(RiskBand.CRITICAL);
    }

    /** The document has to name the branch it looked at even when that branch flagged nothing. */
    @Test
    void namesTheBranch_evenWithNothingFlagged() {
        given(repository.findFlaggedBetween(any(), any(), eq(7L), isNull())).willReturn(List.of());
        given(repository.findBranchName(7L)).willReturn("Celulares");

        FraudReport report = service.generate(SEP_1, SEP_30, 7L, null);

        assertThat(report.branch()).isEqualTo("Celulares");
        assertThat(report.summary()).isEqualTo(FraudSummary.EMPTY);
    }

    /** Filtering by a branch that doesn't exist would echo "Todos" over a report that matched nothing. */
    @Test
    void anUnknownBranch_isRejected_beforeRunningTheQuery() {
        given(repository.findBranchName(99L)).willReturn(null);

        assertThatThrownBy(() -> service.generate(SEP_1, SEP_30, 99L, null))
                .isInstanceOf(UnknownBranchException.class);
        verify(repository, never()).findFlaggedBetween(any(), any(), any(), any());
    }

    @Test
    void withoutABranch_looksNoNameUp() {
        service.generate(SEP_1, SEP_30, null, null);

        verify(repository, never()).findBranchName(any());
    }

    /**
     * The order is the repository's ORDER BY, not a second sort here — see
     * {@code FlaggedCaseRepositoryTests} for what that order actually is (signal count first, band
     * as the tie-break). Re-sorting in the service was the bug: it disagreed with the SQL and with
     * what the screen tells the analyst ("ordenadas por cantidad de señales…").
     */
    /**
     * Equal length, immediately before: 30 days of September compares against 30 days ending the
     * day before it starts, not against "el mes anterior" by name.
     */
    @Test
    void generate_alsoQueriesTheEqualLengthStretchRightBefore() {
        service.generate(SEP_1, SEP_30, null, null);

        verify(repository).findFlaggedBetween(
                Instant.parse("2026-08-02T03:00:00Z"), Instant.parse("2026-09-01T03:00:00Z"), null, null);
        verify(repository).countClaimsBetween(
                Instant.parse("2026-08-02T03:00:00Z"), Instant.parse("2026-09-01T03:00:00Z"), null);
    }

    @Test
    void generate_previousSummary_foldsThePreviousPeriodsRowsSeparately() {
        given(repository.findFlaggedBetween(
                Instant.parse("2026-09-01T03:00:00Z"), Instant.parse("2026-10-01T03:00:00Z"), null, null))
                .willReturn(List.of(row(1, RiskBand.CRITICAL, FraudSignal.HIGH_RISK_SCORE)));
        given(repository.countClaimsBetween(
                Instant.parse("2026-09-01T03:00:00Z"), Instant.parse("2026-10-01T03:00:00Z"), null))
                .willReturn(10L);
        given(repository.findFlaggedBetween(
                Instant.parse("2026-08-02T03:00:00Z"), Instant.parse("2026-09-01T03:00:00Z"), null, null))
                .willReturn(List.of());
        given(repository.countClaimsBetween(
                Instant.parse("2026-08-02T03:00:00Z"), Instant.parse("2026-09-01T03:00:00Z"), null))
                .willReturn(4L);

        FraudReport report = service.generate(SEP_1, SEP_30, null, null);

        assertThat(report.summary().totalClaims()).isEqualTo(10);
        assertThat(report.previousSummary().totalClaims()).isEqualTo(4);
        assertThat(report.previousSummary().flagged()).isEqualTo(0);
    }

    @Test
    void theRows_keepTheRepositorysOrder() {
        given(repository.findFlaggedBetween(any(), any(), isNull(), isNull())).willReturn(List.of(
                row(4, RiskBand.HIGH, FraudSignal.HIGH_RISK_SCORE, FraudSignal.FORENSIC_INCONSISTENCY),
                row(3, RiskBand.CRITICAL, FraudSignal.HIGH_RISK_SCORE),
                row(1, RiskBand.HIGH, FraudSignal.HIGH_RISK_SCORE),
                row(2, null, FraudSignal.FORENSIC_INCONSISTENCY)));

        FraudReport report = service.generate(SEP_1, SEP_30, null, null);

        assertThat(report.rows()).extracting(FraudReportRow::caseId)
                .containsExactly(4L, 3L, 1L, 2L);
    }

    /** The head describes the very rows underneath it — that is the point of computing it from them. */
    @Test
    void summarisesTheRowsItReturns() {
        given(repository.findFlaggedBetween(any(), any(), isNull(), isNull())).willReturn(List.of(
                row(1, RiskBand.CRITICAL, FraudSignal.HIGH_RISK_SCORE, FraudSignal.FORENSIC_INCONSISTENCY),
                row(2, RiskBand.HIGH, FraudSignal.HIGH_RISK_SCORE)));
        given(repository.countClaimsBetween(any(), any(), isNull())).willReturn(20L);

        FraudSummary summary = service.generate(SEP_1, SEP_30, null, null).summary();

        assertThat(summary.flagged()).isEqualTo(2);
        assertThat(summary.multiSignal()).isEqualTo(1);
        assertThat(summary.totalClaims()).isEqualTo(20);
        assertThat(summary.flaggedRate()).isEqualTo(0.1);
    }

    /**
     * The denominator is the period and the branch, and nothing else. With a band filter on, the
     * share still answers "qué parte del período es crítica" — a denominator that shrank with the
     * filter would always read 100% and say nothing.
     */
    @Test
    void theDenominator_followsTheBranchButNotTheAlertLevel() {
        given(repository.findBranchName(7L)).willReturn("Celulares");

        service.generate(SEP_1, SEP_30, 7L, RiskBand.CRITICAL);

        verify(repository).countClaimsBetween(
                eq(Instant.parse("2026-09-01T03:00:00Z")),
                eq(Instant.parse("2026-10-01T03:00:00Z")),
                eq(7L));
    }

    @Test
    void export_rendersWithTheRequestedFormat_andNamesTheFileAfterThePeriod() {
        byte[] pdf = {1, 2, 3};
        given(csvExporter.format()).willReturn(ReportFormat.CSV);
        given(pdfExporter.format()).willReturn(ReportFormat.PDF);
        given(pdfExporter.export(any())).willReturn(pdf);

        ExportedReport file = service.export(SEP_1, SEP_30, null, null, ReportFormat.PDF);

        assertThat(file.filename()).isEqualTo("fraude_2026-09-01_2026-09-30.pdf");
        assertThat(file.format()).isEqualTo(ReportFormat.PDF);
        assertThat(file.content()).isEqualTo(pdf);
        verify(csvExporter, never()).export(any());
    }

    private static FraudReportRow row(long caseId, RiskBand band, FraudSignal... signals) {
        return new FraudReportRow(caseId, "Ana Pérez", "30.111.222", "Celulares", "Hurto",
                Instant.parse("2026-09-10T10:00:00Z"), band, List.of(signals), 1, 0,
                CaseStatus.PENDING_ANALYST_REVIEW, false, false);
    }
}
