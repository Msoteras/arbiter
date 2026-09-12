package ar.edu.utn.frba.arbiter.reports.support;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public final class ReportFixtures {

    public static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T15:00:00Z"), BUENOS_AIRES);

    /** Filed 01/08 07:00 and approved 03/08 09:30, Buenos Aires time: 2 d 2 h 30 min. */
    public static final long APPROVED_ROW_MINUTES = 2 * 24 * 60 + 150;

    private ReportFixtures() {
    }

    public static ResolutionReportRow approvedRow(long caseId) {
        return approvedRow(caseId, "Ana Pérez");
    }

    public static ResolutionReportRow approvedRow(long caseId, String insuredName) {
        return new ResolutionReportRow(caseId, insuredName, "30.111.222", "Celulares", "Robo en vía pública",
                Instant.parse("2026-08-01T10:00:00Z"), Instant.parse("2026-08-03T12:30:00Z"),
                APPROVED_ROW_MINUTES, Classification.LLM_RECOMIENDA_APROBAR, "APPROVE", CaseStatus.APPROVED,
                "Laura Gómez");
    }

    /** Lapsed while waiting for documents: no model run, no decision, never assigned. */
    public static ResolutionReportRow lapsedRow(long caseId) {
        return new ResolutionReportRow(caseId, "Julián Díaz", "28.333.444", "Tecnología Portátil", "Hurto",
                Instant.parse("2025-02-01T13:00:00Z"), Instant.parse("2026-08-02T13:00:00Z"),
                790 * 24 * 60, null, null, CaseStatus.LAPSED, null);
    }

    public static ResolutionReport augustReport(List<ResolutionReportRow> rows) {
        return new ResolutionReport(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), null,
                CLOCK.instant(), rows);
    }
}
