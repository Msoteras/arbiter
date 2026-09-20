package ar.edu.utn.frba.arbiter.reports.support;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSignal;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionSummary;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSummary;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.services.FraudSummaries;
import ar.edu.utn.frba.arbiter.reports.services.ResolutionSummaries;

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

    /** Of those, 8 h waiting on documents from the insured: not the insurer's own time. */
    public static final long APPROVED_ROW_WAITING_MINUTES = 8 * 60;

    private ReportFixtures() {
    }

    public static ResolutionReportRow approvedRow(long caseId) {
        return approvedRow(caseId, "Ana Pérez");
    }

    public static ResolutionReportRow approvedRow(long caseId, String insuredName) {
        return new ResolutionReportRow(caseId, insuredName, "30.111.222", "Celulares", "Robo en vía pública",
                Instant.parse("2026-08-01T10:00:00Z"), Instant.parse("2026-08-03T12:30:00Z"),
                APPROVED_ROW_MINUTES, APPROVED_ROW_WAITING_MINUTES, Classification.LLM_RECOMIENDA_APROBAR,
                "APPROVE", CaseStatus.APPROVED, "Laura Gómez");
    }

    /** Lapsed while waiting for documents: no model run, no decision, never assigned. */
    public static ResolutionReportRow lapsedRow(long caseId) {
        return new ResolutionReportRow(caseId, "Julián Díaz", "28.333.444", "Tecnología Portátil", "Hurto",
                Instant.parse("2025-02-01T13:00:00Z"), Instant.parse("2026-08-02T13:00:00Z"),
                790 * 24 * 60, 780 * 24 * 60, null, null, CaseStatus.LAPSED, null);
    }

    /** Resolved by the deterministic gate: the only rows that count towards the Fast Track share. */
    public static ResolutionReportRow fastTrackRow(long caseId) {
        return new ResolutionReportRow(caseId, "Marcos Ruiz", "33.555.666", "Celulares", "Hurto",
                Instant.parse("2026-08-04T10:00:00Z"), Instant.parse("2026-08-04T12:00:00Z"),
                120, 0, Classification.FAST_TRACK, "APPROVE", CaseStatus.APPROVED, "Laura Gómez");
    }

    /** The summary always comes from the rows, so a fixture can't state a total its rows contradict. */
    public static ResolutionReport augustReport(List<ResolutionReportRow> rows) {
        return augustReport(rows, null, null);
    }

    public static ResolutionReport augustReport(List<ResolutionReportRow> rows, String branch,
                                                String claimCause) {
        return augustReport(rows, branch, claimCause, ResolutionSummary.EMPTY);
    }

    /** With an explicit previous period, for the exporters' "vs. período anterior" line. */
    public static ResolutionReport augustReport(List<ResolutionReportRow> rows, String branch,
                                                String claimCause, ResolutionSummary previousSummary) {
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 8, 31);
        TimelineGranularity granularity = TimelineGranularity.forPeriod(from, to, rows.size());
        return new ResolutionReport(from, to, branch, claimCause, CLOCK.instant(),
                ResolutionSummaries.of(rows), previousSummary, granularity,
                ResolutionSummaries.timeline(rows, from, to, BUENOS_AIRES, granularity), rows);
    }

    /** Flagged on all three signals: a critical band, a repeat claimant and two matched images. */
    public static FraudReportRow flaggedRow(long caseId) {
        return flaggedRow(caseId, "Marcos Aguirre");
    }

    public static FraudReportRow flaggedRow(long caseId, String insuredName) {
        return new FraudReportRow(caseId, insuredName, "28.904.115", "Celulares",
                "Robo en vía pública", Instant.parse("2026-09-12T09:20:00Z"), RiskBand.CRITICAL,
                List.of(FraudSignal.HIGH_RISK_SCORE, FraudSignal.FORENSIC_INCONSISTENCY),
                3, 2, null, CaseStatus.PENDING_EXPERT_REPORT, false, false);
    }

    /**
     * Listed for a reused image, and the score put it in LOW. The band is not an alert — it is the
     * case that made "Bajo" stop being printed as an alert level.
     */
    public static FraudReportRow lowScoreRow(long caseId) {
        return new FraudReportRow(caseId, "Paula Soria", "33.508.901", "Celulares",
                "Rotura accidental", Instant.parse("2026-09-08T08:30:00Z"), RiskBand.LOW,
                List.of(FraudSignal.FORENSIC_INCONSISTENCY), 1, 1, null,
                CaseStatus.PENDING_ANALYST_REVIEW, false, false);
    }

    /** Only the forensic signal, and the scoring never ran: the case with no band at all. */
    public static FraudReportRow unscoredRow(long caseId) {
        return new FraudReportRow(caseId, "Romina Vega", "34.771.009", "Tecnología Portátil", "Hurto",
                Instant.parse("2026-09-05T14:00:00Z"), null,
                List.of(FraudSignal.FORENSIC_INCONSISTENCY), 1, 1, null, CaseStatus.REJECTED, true, true);
    }

    /**
     * Flagged only by the document factor: the declared police-report date doesn't match what the
     * certificate says. The case the signal exists for.
     */
    public static FraudReportRow documentInconsistentRow(long caseId) {
        return new FraudReportRow(caseId, "Nicolás Farías", "31.204.556", "Celulares", "Hurto",
                Instant.parse("2026-09-16T11:10:00Z"), null,
                List.of(FraudSignal.DOCUMENT_INCONSISTENCY), 1, 0,
                "La constancia policial está fechada el 2026-09-14, pero el asegurado declaró haber "
                        + "denunciado el 2026-09-15",
                CaseStatus.PENDING_ANALYST_REVIEW, false, false);
    }

    /** Claims filed in September, the denominator of the rates. Round so the shares read cleanly. */
    public static final long SEPTEMBER_CLAIMS = 20;

    public static FraudReport septemberFraudReport(List<FraudReportRow> rows) {
        return septemberFraudReport(rows, null, null);
    }

    public static FraudReport septemberFraudReport(List<FraudReportRow> rows, String branch,
                                                   RiskBand riskBand) {
        return septemberFraudReport(rows, branch, riskBand, SEPTEMBER_CLAIMS);
    }

    public static FraudReport septemberFraudReport(List<FraudReportRow> rows, String branch,
                                                   RiskBand riskBand, long totalClaims) {
        return septemberFraudReport(rows, branch, riskBand, totalClaims, FraudSummary.EMPTY);
    }

    /** With an explicit previous period, for the exporters' "vs. período anterior" line. */
    public static FraudReport septemberFraudReport(List<FraudReportRow> rows, String branch,
                                                   RiskBand riskBand, long totalClaims,
                                                   FraudSummary previousSummary) {
        return new FraudReport(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), branch, riskBand,
                CLOCK.instant(), FraudSummaries.of(rows, totalClaims), previousSummary, rows);
    }
}
