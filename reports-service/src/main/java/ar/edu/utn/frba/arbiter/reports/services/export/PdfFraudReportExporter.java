package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.reports.dto.FraudReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSummary;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The fraud report as a landscape A4 table. The layout is {@link PdfReportWriter}'s; what lives
 * here is which columns the report has and what goes in each cell.
 *
 * <p>The heading carries the caveat the screen no longer needs to spell out: this document leaves
 * the insurer on its own, with nobody next to it to say that a flagged case is not a determined
 * one.
 */
@Component
@RequiredArgsConstructor
public class PdfFraudReportExporter implements FraudReportExporter {

    private static final String TITLE = "Reporte de detección de fraude";

    private static final String CAVEAT = "El sistema no determina fraude: señala indicios para "
            + "revisión humana. El nivel de alerta es una sugerencia del motor de scoring, no una "
            + "conclusión; \"No alertó\" significa que el score no marcó el expediente y que está "
            + "listado por otra señal.";

    private static final String[] HEADER = {
            "Nº", "Asegurado", "DNI", "Ramo · Hecho generador", "Denuncia", "Alerta", "Indicadores",
            "Estado", "Fraude determinado"};
    /**
     * Sums to 766pt, just inside the 770pt a landscape A4 leaves between margins. The alert level,
     * the status and the determination get room for their longest value; "Indicadores" is the one
     * column allowed to ellipsize, because the full sentence is in the CSV and on screen, and here
     * it is the only cell that can be read from its first words.
     */
    private static final float[] WIDTHS = {36, 100, 58, 110, 52, 44, 164, 90, 112};

    private final Clock clock;

    @Override
    public ReportFormat format() {
        return ReportFormat.PDF;
    }

    @Override
    public byte[] export(FraudReport report) {
        ZoneId zone = clock.getZone();
        return PdfReportWriter.render(new PdfReportWriter.Spec(
                TITLE,
                headingLines(report),
                HEADER,
                WIDTHS,
                report.rows().stream().map(row -> cells(row, zone)).toList(),
                "Ningún expediente con indicios en el período.",
                report.generatedAt(),
                zone));
    }

    private static List<String> headingLines(FraudReport report) {
        List<String> lines = new ArrayList<>();
        lines.add(CAVEAT);
        lines.add("Período: %s al %s · Ramo: %s · Nivel de alerta: %s".formatted(
                ReportLabels.DATE.format(report.from()),
                ReportLabels.DATE.format(report.to()),
                ReportLabels.filterValue(report.branch()),
                report.riskBand() == null ? "Todos" : ReportLabels.alertLevel(report.riskBand())));
        lines.addAll(summaryLines(report.summary()));
        return lines;
    }

    private static List<String> summaryLines(FraudSummary summary) {
        if (summary.flagged() == 0) {
            return List.of();
        }
        return List.of(
                // The denominator travels with every rate: a share with no population behind it is
                // the figure people misread the fastest.
                ("%s%d de %d %s con indicios (%s) · Con señales cruzadas: %d · "
                        + "Fraude determinado: %d (%s del período, %d con respaldo pericial)")
                        .formatted(
                                PdfReportWriter.TOTALS_LABEL,
                                summary.flagged(),
                                summary.totalClaims(),
                                summary.totalClaims() == 1 ? "denuncia" : "denuncias",
                                ReportLabels.percent(summary.flaggedRate()),
                                summary.multiSignal(),
                                summary.fraudDetermined(),
                                ReportLabels.percent(summary.fraudRate()),
                                summary.backedByExpert()),
                "Por nivel de alerta: " + distribution(summary.byAlertLevel(),
                        count -> ReportLabels.alertLevel(count.label())),
                // The buckets overlap — a case with two signals is in both — so they can add up to
                // more than the total. Said here so nobody reads it as an arithmetic error.
                "Por señal (un expediente puede tener más de una): "
                        + distribution(summary.bySignal(), count -> ReportLabels.signal(count.label())));
    }

    private static String distribution(List<MetricCount> counts, Function<MetricCount, String> label) {
        return counts.stream()
                .map(count -> "%s %d".formatted(label.apply(count), count.count()))
                .collect(Collectors.joining(" · "));
    }

    private static String[] cells(FraudReportRow row, ZoneId zone) {
        return new String[]{
                String.valueOf(row.caseId()),
                row.insuredName(),
                row.insuredDni(),
                row.branch() + " · " + row.claimCause(),
                ReportLabels.DATE.withZone(zone).format(row.reportedAt()),
                ReportLabels.alertLevel(row.riskBand()),
                ReportLabels.signals(row),
                ReportLabels.status(row.status()),
                ReportLabels.fraudDetermination(row)};
    }
}
