package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.reports.dto.FraudReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/** One row per flagged case. The dialect and the escaping are {@link CsvWriter}'s. */
@Component
@RequiredArgsConstructor
public class CsvFraudReportExporter implements FraudReportExporter {

    /**
     * The two counts get columns of their own besides the "Señales" sentence: a spreadsheet is
     * opened to sort and filter, and "3 denuncias en 12 meses" is text you can't order by.
     */
    private static final List<String> HEADER = List.of(
            "Nº expediente", "Asegurado", "DNI", "Ramo", "Hecho generador", "Fecha de denuncia",
            "Nivel de alerta", "Señales", "Denuncias en 12 meses", "Imágenes con coincidencia",
            "Estado", "Fraude determinado");

    private final Clock clock;

    @Override
    public ReportFormat format() {
        return ReportFormat.CSV;
    }

    @Override
    public byte[] export(FraudReport report) {
        DateTimeFormatter dateTime = ReportLabels.DATE_TIME.withZone(clock.getZone());
        CsvWriter csv = new CsvWriter();
        csv.appendLine(HEADER);
        for (FraudReportRow row : report.rows()) {
            csv.appendLine(Arrays.asList(
                    String.valueOf(row.caseId()),
                    row.insuredName(),
                    row.insuredDni(),
                    row.branch(),
                    row.claimCause(),
                    dateTime.format(row.reportedAt()),
                    ReportLabels.alertLevel(row.riskBand()),
                    ReportLabels.signals(row),
                    String.valueOf(row.claimsInWindow()),
                    String.valueOf(row.suspiciousImages()),
                    ReportLabels.status(row.status()),
                    ReportLabels.fraudDetermination(row)));
        }
        return csv.toBytes();
    }
}
