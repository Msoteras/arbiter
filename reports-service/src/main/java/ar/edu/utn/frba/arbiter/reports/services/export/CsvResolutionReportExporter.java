package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * One row per case, no preamble: the period is in the file name, and a header-only first line keeps
 * the file loadable by anything that reads CSV, not just by a person.
 */
@Component
@RequiredArgsConstructor
public class CsvResolutionReportExporter implements ResolutionReportExporter {

    // ';' and not ',': Excel set to es-AR uses the comma as its decimal separator and expects ';'
    // between fields — with ',' every row lands whole in column A.
    private static final String SEPARATOR = ";";
    private static final String LINE_END = "\r\n";
    // Without the BOM Excel reads the file as ANSI and mangles every accent and ñ.
    private static final String BOM = String.valueOf((char) 0xFEFF);
    // A cell starting with one of these is a formula to a spreadsheet (CSV injection). Names come
    // from the insurer's database, not from us.
    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    private static final List<String> HEADER = List.of(
            "Nº expediente", "Asegurado", "DNI", "Ramo", "Hecho generador", "Fecha de denuncia",
            "Fecha de resolución", "Tiempo total (horas)", "Clasificación", "Decisión del analista",
            "Estado final", "Analista");

    private final Clock clock;

    @Override
    public ReportFormat format() {
        return ReportFormat.CSV;
    }

    @Override
    public byte[] export(ResolutionReport report) {
        DateTimeFormatter dateTime = ReportLabels.DATE_TIME.withZone(clock.getZone());
        StringBuilder csv = new StringBuilder(BOM);
        appendLine(csv, HEADER);
        for (ResolutionReportRow row : report.rows()) {
            appendLine(csv, Arrays.asList(
                    String.valueOf(row.caseId()),
                    row.insuredName(),
                    row.insuredDni(),
                    row.branch(),
                    row.claimCause(),
                    dateTime.format(row.reportedAt()),
                    dateTime.format(row.resolvedAt()),
                    ReportLabels.hours(row.totalMinutes()),
                    ReportLabels.classification(row.classification()),
                    ReportLabels.decision(row.analystDecision()),
                    ReportLabels.status(row.finalStatus()),
                    row.analystName()));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendLine(StringBuilder csv, List<String> fields) {
        csv.append(fields.stream()
                        .map(CsvResolutionReportExporter::escape)
                        .collect(Collectors.joining(SEPARATOR)))
                .append(LINE_END);
    }

    static String escape(String field) {
        String value = field == null ? "" : field;
        if (!value.isEmpty() && FORMULA_TRIGGERS.indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        if (value.contains(SEPARATOR) || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            value = "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
