package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ComparisonMode;
import ar.edu.utn.frba.arbiter.reports.dto.ComparisonRequest;
import ar.edu.utn.frba.arbiter.reports.dto.ExportedReport;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.dto.ReportPeriod;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionSummary;
import ar.edu.utn.frba.arbiter.reports.exceptions.InvalidReportPeriodException;
import ar.edu.utn.frba.arbiter.reports.exceptions.TenantNotResolvedException;
import ar.edu.utn.frba.arbiter.reports.exceptions.UnknownBranchException;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ResolvedCaseRepository;
import ar.edu.utn.frba.arbiter.reports.services.export.ResolutionReportExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.CLOCK;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.approvedRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.fastTrackRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.lapsedRow;
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
class ResolutionReportServiceTest {

    private static final LocalDate AUG_1 = LocalDate.of(2026, 8, 1);
    private static final LocalDate AUG_31 = LocalDate.of(2026, 8, 31);

    @Mock
    private ResolvedCaseRepository repository;

    @Mock
    private ResolutionReportExporter csvExporter;

    @Mock
    private ResolutionReportExporter pdfExporter;

    @Mock
    private DailyMetricsService dailyMetricsService;

    private ResolutionReportService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("arbiter_bbva");
        service = new ResolutionReportService(repository, dailyMetricsService, new ReportPeriods(CLOCK),
                List.of(csvExporter, pdfExporter), CLOCK);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void generate_readsWholeDaysInTheInsurersLocalTime() {
        ResolutionReport report = service.generate(AUG_1, AUG_31, null, null, ComparisonRequest.DEFAULT);

        // 00:00 in Buenos Aires is 03:00 UTC; the last day runs up to the next local midnight.
        verify(repository).findResolvedBetween(
                Instant.parse("2026-08-01T03:00:00Z"), Instant.parse("2026-09-01T03:00:00Z"), null, null);
        assertThat(report.from()).isEqualTo(AUG_1);
        assertThat(report.to()).isEqualTo(AUG_31);
        assertThat(report.generatedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void generate_aSingleDayIsAWholeDay() {
        LocalDate day = LocalDate.of(2026, 8, 15);

        service.generate(day, day, null, null, ComparisonRequest.DEFAULT);

        verify(repository).findResolvedBetween(
                Instant.parse("2026-08-15T03:00:00Z"), Instant.parse("2026-08-16T03:00:00Z"), null, null);
    }

    @Test
    void generate_blankClaimCauseMeansEveryCause_andAGivenOneIsTrimmed() {
        service.generate(AUG_1, AUG_31, null, "   ", ComparisonRequest.DEFAULT);
        service.generate(AUG_1, AUG_31, null, "  Hurto ", ComparisonRequest.DEFAULT);

        // The exact period, not any(): a wildcard would also match the previous-period query.
        verify(repository).findResolvedBetween(
                eq(Instant.parse("2026-08-01T03:00:00Z")), eq(Instant.parse("2026-09-01T03:00:00Z")),
                isNull(), isNull());
        verify(repository).findResolvedBetween(
                eq(Instant.parse("2026-08-01T03:00:00Z")), eq(Instant.parse("2026-09-01T03:00:00Z")),
                isNull(), eq("Hurto"));
    }

    @Test
    void generate_passesTheBranchDown_andEchoesItsNameBack() {
        given(repository.findBranchName(7L)).willReturn("Celulares");

        ResolutionReport report = service.generate(AUG_1, AUG_31, 7L, null, ComparisonRequest.DEFAULT);

        verify(repository).findResolvedBetween(
                eq(Instant.parse("2026-08-01T03:00:00Z")), eq(Instant.parse("2026-09-01T03:00:00Z")),
                eq(7L), isNull());
        assertThat(report.branch()).isEqualTo("Celulares");
    }

    /** An unknown branch would otherwise echo "all branches" over a report that matched nothing. */
    @Test
    void generate_rejectsAnUnknownBranch_beforeRunningTheQuery() {
        given(repository.findBranchName(99L)).willReturn(null);

        assertThatThrownBy(() -> service.generate(AUG_1, AUG_31, 99L, null, ComparisonRequest.DEFAULT))
                .isInstanceOf(UnknownBranchException.class);
        verify(repository, never()).findResolvedBetween(any(), any(), any(), any());
    }

    /** The document has to name the branch it looked at even when that branch closed nothing. */
    @Test
    void generate_namesTheBranch_evenWithNoResolvedCases() {
        given(repository.findResolvedBetween(any(), any(), eq(7L), isNull())).willReturn(List.of());
        given(repository.findBranchName(7L)).willReturn("Celulares");

        ResolutionReport report = service.generate(AUG_1, AUG_31, 7L, null, ComparisonRequest.DEFAULT);

        assertThat(report.branch()).isEqualTo("Celulares");
        assertThat(report.summary()).isEqualTo(ResolutionSummary.EMPTY);
    }

    @Test
    void generate_withoutABranch_looksNoNameUp() {
        service.generate(AUG_1, AUG_31, null, null, ComparisonRequest.DEFAULT);

        verify(repository, never()).findBranchName(any());
    }

    /** The head describes the very rows underneath it. */
    @Test
    void generate_summarisesTheRowsItReturns() {
        given(repository.findResolvedBetween(any(), any(), isNull(), isNull()))
                .willReturn(List.of(approvedRow(1), fastTrackRow(2), lapsedRow(3)));

        ResolutionReport report = service.generate(AUG_1, AUG_31, null, null, ComparisonRequest.DEFAULT);

        assertThat(report.summary().totalCases()).isEqualTo(report.rows().size());
        assertThat(report.summary().fastTrackCases()).isEqualTo(1);
    }

    /** Equal length, immediately before: 31 days of August compare against 31 days of July. */
    @Test
    void generate_alsoQueriesTheEqualLengthStretchRightBefore() {
        service.generate(AUG_1, AUG_31, null, null, ComparisonRequest.DEFAULT);

        verify(repository).findResolvedBetween(
                Instant.parse("2026-07-01T03:00:00Z"), Instant.parse("2026-08-01T03:00:00Z"), null, null);
    }

    @Test
    void generate_comparisonSummary_foldsTheComparisonPeriodsRowsSeparately() {
        given(repository.findResolvedBetween(
                Instant.parse("2026-08-01T03:00:00Z"), Instant.parse("2026-09-01T03:00:00Z"), null, null))
                .willReturn(List.of(approvedRow(1), fastTrackRow(2)));
        given(repository.findResolvedBetween(
                Instant.parse("2026-07-01T03:00:00Z"), Instant.parse("2026-08-01T03:00:00Z"), null, null))
                .willReturn(List.of(lapsedRow(3)));

        ResolutionReport report = service.generate(AUG_1, AUG_31, null, null, ComparisonRequest.DEFAULT);

        assertThat(report.summary().totalCases()).isEqualTo(2);
        assertThat(report.comparisonSummary().totalCases()).isEqualTo(1);
    }

    @Test
    void generate_aStoredComparisonPeriod_isNotReadCaseByCase() {
        ReportPeriod july = new ReportPeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        ResolutionSummary stored = new ResolutionSummary(9, 8, 1440.0, 0.0, 2, 2d / 9, List.of(), List.of());
        given(dailyMetricsService.resolutionSummary(july, 7L, "Hurto")).willReturn(Optional.of(stored));
        given(repository.findBranchName(7L)).willReturn("Celulares");

        ResolutionReport report = service.generate(AUG_1, AUG_31, 7L, "Hurto", ComparisonRequest.DEFAULT);

        assertThat(report.comparisonSummary()).isEqualTo(stored);
        verify(repository, never()).findResolvedBetween(
                eq(Instant.parse("2026-07-01T03:00:00Z")), any(), any(), any());
    }

    @Test
    void generate_comparingWithLastYear_readsTheSameDatesAYearEarlier() {
        ResolutionReport report = service.generate(AUG_1, AUG_31, null, null,
                new ComparisonRequest(ComparisonMode.SAME_PERIOD_LAST_YEAR, null, null));

        assertThat(report.comparison().from()).isEqualTo(LocalDate.of(2025, 8, 1));
        verify(repository).findResolvedBetween(
                Instant.parse("2025-08-01T03:00:00Z"), Instant.parse("2025-09-01T03:00:00Z"), null, null);
    }

    @Test
    void generate_fromAfterTo_isRejected() {
        assertThatThrownBy(() -> service.generate(AUG_31, AUG_1, null, null, ComparisonRequest.DEFAULT))
                .isInstanceOf(InvalidReportPeriodException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void generate_aPeriodLongerThanAYear_isRejected_butALeapYearFits() {
        assertThatThrownBy(() ->
                service.generate(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 2), null, null, ComparisonRequest.DEFAULT))
                .isInstanceOf(InvalidReportPeriodException.class);

        service.generate(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), null, null, ComparisonRequest.DEFAULT);
    }

    @Test
    void generate_withoutAnInsurerInTheToken_isRejected() {
        TenantContext.clear();

        assertThatThrownBy(() -> service.generate(AUG_1, AUG_31, null, null, ComparisonRequest.DEFAULT))
                .isInstanceOf(TenantNotResolvedException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void export_rendersWithTheRequestedFormat_andNamesTheFileAfterThePeriod() {
        byte[] pdf = {1, 2, 3};
        given(csvExporter.format()).willReturn(ReportFormat.CSV);
        given(pdfExporter.format()).willReturn(ReportFormat.PDF);
        given(pdfExporter.export(any())).willReturn(pdf);

        ExportedReport file = service.export(AUG_1, AUG_31, null, null, ComparisonRequest.DEFAULT, ReportFormat.PDF);

        assertThat(file.filename()).isEqualTo("resoluciones_2026-08-01_2026-08-31.pdf");
        assertThat(file.format()).isEqualTo(ReportFormat.PDF);
        assertThat(file.content()).isEqualTo(pdf);
        verify(csvExporter, never()).export(any());
    }

    /** The previous period is queried under the SAME filters, or the trend would mix populations. */
    @Test
    void generate_comparesAgainstTheEquallyLongPeriodBefore_underTheSameFilters() {
        given(repository.findBranchName(7L)).willReturn("Celulares");

        service.generate(AUG_1, AUG_31, 7L, "Hurto", ComparisonRequest.DEFAULT);

        // August is 31 days, so the comparison window is the 31 days before it: all of July.
        verify(repository).findResolvedBetween(
                Instant.parse("2026-07-01T03:00:00Z"), Instant.parse("2026-08-01T03:00:00Z"),
                7L, "Hurto");
    }
}
