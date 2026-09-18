package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/** One row per resolved case. The dialect and the escaping are {@link CsvWriter}'s. */
@Component
@RequiredArgsConstructor
public class CsvResolutionReportExporter implements ResolutionReportExporter {

    private static final List<String> HEADER = List.of(
            "Nº expediente", "Asegurado", "DNI", "Ramo", "Hecho generador", "Fecha de denuncia",
            "Fecha de resolución", "Tiempo total (horas)", "Tiempo esperando a terceros (horas)",
            "Clasificación", "Decisión del analista",
            "Estado final", "Analista");

    private final Clock clock;

    @Override
    public ReportFormat format() {
        return ReportFormat.CSV;
    }

    @Override
    public byte[] export(ResolutionReport report) {
        DateTimeFormatter dateTime = ReportLabels.DATE_TIME.withZone(clock.getZone());
        CsvWriter csv = new CsvWriter();
        csv.appendLine(HEADER);
        for (ResolutionReportRow row : report.rows()) {
            csv.appendLine(Arrays.asList(
                    String.valueOf(row.caseId()),
                    row.insuredName(),
                    row.insuredDni(),
                    row.branch(),
                    row.claimCause(),
                    dateTime.format(row.reportedAt()),
                    dateTime.format(row.resolvedAt()),
                    ReportLabels.hours(row.totalMinutes()),
                    ReportLabels.hours(row.waitingMinutes()),
                    ReportLabels.classification(row.classification()),
                    ReportLabels.decision(row.analystDecision()),
                    ReportLabels.status(row.finalStatus()),
                    row.analystName()));
        }
        return csv.toBytes();
    }
}
