package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ExportedReport;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
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

    private ResolutionReportService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("arbiter_bbva");
        service = new ResolutionReportService(repository, List.of(csvExporter, pdfExporter), CLOCK);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void generate_readsWholeDaysInTheInsurersLocalTime() {
        ResolutionReport report = service.generate(AUG_1, AUG_31, null, null);

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

        service.generate(day, day, null, null);

        verify(repository).findResolvedBetween(
                Instant.parse("2026-08-15T03:00:00Z"), Instant.parse("2026-08-16T03:00:00Z"), null, null);
    }

    @Test
    void generate_blankClaimCauseMeansEveryCause_andAGivenOneIsTrimmed() {
        service.generate(AUG_1, AUG_31, null, "   ");
        service.generate(AUG_1, AUG_31, null, "  Hurto ");

        // The exact period, not any(), any()): that wildcard also matches the previous-period query
        // generate() now makes, and a loose verify here can't tell the two calls apart.
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

        ResolutionReport report = service.generate(AUG_1, AUG_31, 7L, null);

        verify(repository).findResolvedBetween(
                eq(Instant.parse("2026-08-01T03:00:00Z")), eq(Instant.parse("2026-09-01T03:00:00Z")),
                eq(7L), isNull());
        assertThat(report.branch()).isEqualTo("Celulares");
    }

    /** Filtering by a branch that doesn't exist would echo "Todos" over a report that matched nothing. */
    @Test
    void generate_rejectsAnUnknownBranch_beforeRunningTheQuery() {
        given(repository.findBranchName(99L)).willReturn(null);

        assertThatThrownBy(() -> service.generate(AUG_1, AUG_31, 99L, null))
                .isInstanceOf(UnknownBranchException.class);
        verify(repository, never()).findResolvedBetween(any(), any(), any(), any());
    }

    /** The document has to name the branch it looked at even when that branch closed nothing. */
    @Test
    void generate_namesTheBranch_evenWithNoResolvedCases() {
        given(repository.findResolvedBetween(any(), any(), eq(7L), isNull())).willReturn(List.of());
        given(repository.findBranchName(7L)).willReturn("Celulares");

        ResolutionReport report = service.generate(AUG_1, AUG_31, 7L, null);

        assertThat(report.branch()).isEqualTo("Celulares");
        assertThat(report.summary()).isEqualTo(ResolutionSummary.EMPTY);
    }

    @Test
    void generate_withoutABranch_looksNoNameUp() {
        service.generate(AUG_1, AUG_31, null, null);

        verify(repository, never()).findBranchName(any());
    }

    /** The head describes the very rows underneath it — that is the point of computing it from them. */
    @Test
    void generate_summarisesTheRowsItReturns() {
        given(repository.findResolvedBetween(any(), any(), isNull(), isNull()))
                .willReturn(List.of(approvedRow(1), fastTrackRow(2), lapsedRow(3)));

        ResolutionReport report = service.generate(AUG_1, AUG_31, null, null);

        assertThat(report.summary().totalCases()).isEqualTo(report.rows().size());
        assertThat(report.summary().fastTrackCases()).isEqualTo(1);
    }

    /**
     * Equal length, immediately before: 31 days of August compares against 31 days of July, not
     * against "el mes anterior" by name.
     */
    @Test
    void generate_alsoQueriesTheEqualLengthStretchRightBefore() {
        service.generate(AUG_1, AUG_31, null, null);

        verify(repository).findResolvedBetween(
                Instant.parse("2026-07-01T03:00:00Z"), Instant.parse("2026-08-01T03:00:00Z"), null, null);
    }

    @Test
    void generate_previousSummary_foldsThePreviousPeriodsRowsSeparately() {
        given(repository.findResolvedBetween(
                Instant.parse("2026-08-01T03:00:00Z"), Instant.parse("2026-09-01T03:00:00Z"), null, null))
                .willReturn(List.of(approvedRow(1), fastTrackRow(2)));
        given(repository.findResolvedBetween(
                Instant.parse("2026-07-01T03:00:00Z"), Instant.parse("2026-08-01T03:00:00Z"), null, null))
                .willReturn(List.of(lapsedRow(3)));

        ResolutionReport report = service.generate(AUG_1, AUG_31, null, null);

        assertThat(report.summary().totalCases()).isEqualTo(2);
        assertThat(report.previousSummary().totalCases()).isEqualTo(1);
    }

    @Test
    void generate_fromAfterTo_isRejected() {
        assertThatThrownBy(() -> service.generate(AUG_31, AUG_1, null, null))
                .isInstanceOf(InvalidReportPeriodException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void generate_aPeriodLongerThanAYear_isRejected_butALeapYearFits() {
        assertThatThrownBy(() ->
                service.generate(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 2), null, null))
                .isInstanceOf(InvalidReportPeriodException.class);

        service.generate(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), null, null);
    }

    @Test
    void generate_withoutAnInsurerInTheToken_isRejected() {
        TenantContext.clear();

        assertThatThrownBy(() -> service.generate(AUG_1, AUG_31, null, null))
                .isInstanceOf(TenantNotResolvedException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void export_rendersWithTheRequestedFormat_andNamesTheFileAfterThePeriod() {
        byte[] pdf = {1, 2, 3};
        given(csvExporter.format()).willReturn(ReportFormat.CSV);
        given(pdfExporter.format()).willReturn(ReportFormat.PDF);
        given(pdfExporter.export(any())).willReturn(pdf);

        ExportedReport file = service.export(AUG_1, AUG_31, null, null, ReportFormat.PDF);

        assertThat(file.filename()).isEqualTo("resoluciones_2026-08-01_2026-08-31.pdf");
        assertThat(file.format()).isEqualTo(ReportFormat.PDF);
        assertThat(file.content()).isEqualTo(pdf);
        verify(csvExporter, never()).export(any());
    }

    /**
     * The comparison the screen turns into arrows: the period immediately before, of equal length
     * and under the SAME filters. August compares against July — and a report cut by branch
     * compares against that branch, not against the whole portfolio, or the direction would come
     * from a different population than the figure it sits next to.
     */
    @Test
    void generate_comparesAgainstTheEquallyLongPeriodBefore_underTheSameFilters() {
        given(repository.findBranchName(7L)).willReturn("Celulares");

        service.generate(AUG_1, AUG_31, 7L, "Hurto");

        // August is 31 days, so the comparison window is the 31 days before it: all of July.
        verify(repository).findResolvedBetween(
                Instant.parse("2026-07-01T03:00:00Z"), Instant.parse("2026-08-01T03:00:00Z"),
                7L, "Hurto");
    }
}
