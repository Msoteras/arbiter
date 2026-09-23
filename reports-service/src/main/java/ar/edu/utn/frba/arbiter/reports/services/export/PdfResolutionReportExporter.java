package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The resolution report's columns and cells; the layout is {@link PdfReportWriter}'s. */
@Component
@RequiredArgsConstructor
public class PdfResolutionReportExporter implements ResolutionReportExporter {

    private static final String TITLE = "Reporte de resolución de siniestros";

    private static final String[] HEADER = {
            "Nº", "Asegurado", "DNI", "Ramo · Hecho generador", "Denuncia", "Resolución", "Tiempo",
            "Clasificación", "Decisión", "Estado", "Analista"};
    /** Sums to 766pt, just inside the 770pt a landscape A4 leaves between margins. */
    private static final float[] WIDTHS = {36, 110, 58, 120, 56, 56, 50, 88, 50, 56, 86};

    private final Clock clock;

    @Override
    public ReportFormat format() {
        return ReportFormat.PDF;
    }

    @Override
    public byte[] export(ResolutionReport report) {
        ZoneId zone = clock.getZone();
        return PdfReportWriter.render(new PdfReportWriter.Spec(
                TITLE,
                headingLines(report),
                HEADER,
                WIDTHS,
                report.rows().stream().map(row -> cells(row, zone)).toList(),
                "No hay siniestros resueltos en el período.",
                report.generatedAt(),
                zone));
    }

    private static List<String> headingLines(ResolutionReport report) {
        List<String> lines = new ArrayList<>();
        lines.add("Período: %s al %s · Ramo: %s · Tipo de siniestro: %s".formatted(
                ReportLabels.DATE.format(report.from()),
                ReportLabels.DATE.format(report.to()),
                ReportLabels.filterValue(report.branch()),
                ReportLabels.filterValue(report.claimCause())));
        lines.addAll(summaryLines(report.summary(), report.previousSummary()));
        return lines;
    }

    private static List<String> summaryLines(ResolutionSummary summary, ResolutionSummary previous) {
        if (summary.totalCases() == 0) {
            return List.of();
        }
        return List.of(
                "%s%d %s · %s · Fast Track: %d (%s)".formatted(
                        PdfReportWriter.TOTALS_LABEL,
                        summary.totalCases(),
                        summary.totalCases() == 1 ? "siniestro resuelto" : "siniestros resueltos",
                        resolutionTime(summary),
                        summary.fastTrackCases(),
                        ReportLabels.percent(summary.fastTrackRate())),
                comparisonLine(summary, previous),
                "Por estado: " + distribution(summary.byStatus(),
                        count -> ReportLabels.status(CaseStatus.valueOf(count.label()))),
                "Por tipo de siniestro: " + distribution(summary.byClaimCause(), MetricCount::label));
    }

    /** Change against the previous period of equal length. */
    private static String comparisonLine(ResolutionSummary summary, ResolutionSummary previous) {
        long previousCases = previous.totalCases();
        String previousTime = previous.averageMinutes() == null
                ? "sin tiempo promedio"
                : ReportLabels.duration(Math.round(previous.averageMinutes()));
        return "Vs. período anterior: %d %s%s · Tiempo promedio: %s%s · Fast Track: %s%s".formatted(
                previousCases,
                previousCases == 1 ? "siniestro resuelto" : "siniestros resueltos",
                ReportLabels.countDelta(summary.totalCases(), previousCases, previousCases),
                previousTime,
                ReportLabels.durationDelta(
                        summary.averageMinutes(), previous.averageMinutes(), previous.decidedCases()),
                ReportLabels.percent(previous.fastTrackRate()),
                ReportLabels.rateDelta(summary.fastTrackRate(), previous.fastTrackRate(), previousCases));
    }

    /**
     * States what the average is over (decided cases only), or it would read as covering every row,
     * and splits it into the insurer's own time and the wait on third parties.
     */
    private static String resolutionTime(ResolutionSummary summary) {
        if (summary.averageMinutes() == null) {
            return "Ningún expediente decidido: sin tiempo promedio";
        }
        long total = Math.round(summary.averageMinutes());
        long waiting = Math.round(summary.averageWaitingMinutes());
        return "Tiempo promedio de resolución: %s sobre %d %s (%s de gestión · %s esperando a terceros)"
                .formatted(
                        ReportLabels.duration(total),
                        summary.decidedCases(),
                        summary.decidedCases() == 1 ? "decidido" : "decididos",
                        ReportLabels.duration(total - waiting),
                        ReportLabels.duration(waiting));
    }

    private static String distribution(List<MetricCount> counts, Function<MetricCount, String> label) {
        return counts.stream()
                .map(count -> "%s %d".formatted(label.apply(count), count.count()))
                .collect(Collectors.joining(" · "));
    }

    private static String[] cells(ResolutionReportRow row, ZoneId zone) {
        return new String[]{
                String.valueOf(row.caseId()),
                row.insuredName(),
                row.insuredDni(),
                row.branch() + " · " + row.claimCause(),
                ReportLabels.DATE.withZone(zone).format(row.reportedAt()),
                ReportLabels.DATE.withZone(zone).format(row.resolvedAt()),
                ReportLabels.duration(row.totalMinutes()),
                ReportLabels.classification(row.classification()),
                ReportLabels.decision(row.analystDecision()),
                ReportLabels.status(row.finalStatus()),
                row.analystName() == null ? "—" : row.analystName()};
    }
}
