package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ExportedReport;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;
import ar.edu.utn.frba.arbiter.reports.exceptions.InvalidReportPeriodException;
import ar.edu.utn.frba.arbiter.reports.exceptions.TenantNotResolvedException;
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
        ResolutionReport report = service.generate(AUG_1, AUG_31, null);

        // 00:00 in Buenos Aires is 03:00 UTC; the last day runs up to the next local midnight.
        verify(repository).findResolvedBetween(
                Instant.parse("2026-08-01T03:00:00Z"), Instant.parse("2026-09-01T03:00:00Z"), null);
        assertThat(report.from()).isEqualTo(AUG_1);
        assertThat(report.to()).isEqualTo(AUG_31);
        assertThat(report.generatedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void generate_aSingleDayIsAWholeDay() {
        LocalDate day = LocalDate.of(2026, 8, 15);

        service.generate(day, day, null);

        verify(repository).findResolvedBetween(
                Instant.parse("2026-08-15T03:00:00Z"), Instant.parse("2026-08-16T03:00:00Z"), null);
    }

    @Test
    void generate_blankClaimCauseMeansEveryCause_andAGivenOneIsTrimmed() {
        service.generate(AUG_1, AUG_31, "   ");
        service.generate(AUG_1, AUG_31, "  Hurto ");

        verify(repository).findResolvedBetween(any(), any(), isNull());
        verify(repository).findResolvedBetween(any(), any(), eq("Hurto"));
    }

    @Test
    void generate_fromAfterTo_isRejected() {
        assertThatThrownBy(() -> service.generate(AUG_31, AUG_1, null))
                .isInstanceOf(InvalidReportPeriodException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void generate_aPeriodLongerThanAYear_isRejected_butALeapYearFits() {
        assertThatThrownBy(() -> service.generate(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 2), null))
                .isInstanceOf(InvalidReportPeriodException.class);

        service.generate(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), null);
    }

    @Test
    void generate_withoutAnInsurerInTheToken_isRejected() {
        TenantContext.clear();

        assertThatThrownBy(() -> service.generate(AUG_1, AUG_31, null))
                .isInstanceOf(TenantNotResolvedException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void export_rendersWithTheRequestedFormat_andNamesTheFileAfterThePeriod() {
        byte[] pdf = {1, 2, 3};
        given(csvExporter.format()).willReturn(ReportFormat.CSV);
        given(pdfExporter.format()).willReturn(ReportFormat.PDF);
        given(pdfExporter.export(any())).willReturn(pdf);

        ExportedReport file = service.export(AUG_1, AUG_31, null, ReportFormat.PDF);

        assertThat(file.filename()).isEqualTo("resoluciones_2026-08-01_2026-08-31.pdf");
        assertThat(file.format()).isEqualTo(ReportFormat.PDF);
        assertThat(file.content()).isEqualTo(pdf);
        verify(csvExporter, never()).export(any());
    }
}
