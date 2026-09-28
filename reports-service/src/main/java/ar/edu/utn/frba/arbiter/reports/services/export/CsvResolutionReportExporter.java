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

    /**
     * The durations read as they do on the PDF ("61 d 11 h"), so the same case says the same thing
     * in both. They carry their unit in the value and no decimal separator, which is what kept the
     * file out of the locale trap a formatted "1475,3" put it in: that reads as a number on an es-AR
     * machine and as text on an en-US one.
     */
    private static final List<String> HEADER = List.of(
            "Nº expediente", "Asegurado", "DNI", "Ramo", "Hecho generador", "Fecha de denuncia",
            "Fecha de resolución", "Tiempo total", "Tiempo esperando a terceros",
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
                    ReportLabels.duration(row.totalMinutes()),
                    ReportLabels.duration(row.waitingMinutes()),
                    ReportLabels.classification(row.classification()),
                    ReportLabels.decision(row.analystDecision()),
                    ReportLabels.status(row.finalStatus()),
                    row.analystName()));
        }
        return csv.toBytes();
    }
}
