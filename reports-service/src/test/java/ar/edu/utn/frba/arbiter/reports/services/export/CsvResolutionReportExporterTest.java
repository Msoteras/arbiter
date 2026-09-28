package ar.edu.utn.frba.arbiter.reports.services.export;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.CLOCK;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.approvedRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.augustReport;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.lapsedRow;
import static org.assertj.core.api.Assertions.assertThat;

class CsvResolutionReportExporterTest {

    private static final String HEADER = "Nº expediente;Asegurado;DNI;Ramo;Hecho generador;Fecha de denuncia;"
            + "Fecha de resolución;Tiempo total;Tiempo esperando a terceros;"
            + "Clasificación;Decisión del analista;Estado final;Analista";

    private final CsvResolutionReportExporter exporter = new CsvResolutionReportExporter(CLOCK);

    @Test
    void writesABomTheHeaderAndOneLinePerCase_inLocalTime() {
        String csv = export(List.of(approvedRow(42), lapsedRow(43)));

        assertThat(csv.charAt(0)).isEqualTo((char) 0xFEFF);
        assertThat(lines(csv)).containsExactly(
                HEADER,
                "42;Ana Pérez;30.111.222;Celulares;Robo en vía pública;01/08/2026 07:00;03/08/2026 09:30;"
                        + "2 d 2 h;8 h;Recomienda aprobar;Aprobó;Aprobado;Laura Gómez",
                "43;Julián Díaz;28.333.444;Tecnología Portátil;Hurto;01/02/2025 10:00;02/08/2026 10:00;"
                        + "790 d;780 d;Sin clasificación;Sin decisión;Caducado;");
    }

    /**
     * Excel takes the delimiter from the machine's regional settings, not from the file: without
     * this line an en-US machine splits on the comma and the values land under the wrong headers.
     */
    @Test
    void declaresItsSeparator_soExcelDoesNotGuessItFromTheLocale() {
        String csv = export(List.of(approvedRow(42)));

        assertThat(csv).startsWith((char) 0xFEFF + "sep=;\r\n");
    }

    /** The same wording as the PDF, and no decimal separator to be read differently per locale. */
    @Test
    void writesDurationsAsTheyReadOnThePdf() {
        String csv = export(List.of(approvedRow(42)));

        assertThat(csv).contains("Tiempo esperando a terceros");
        // 2 d 2 h 30 min in total, of which 8 h were spent waiting on the insured.
        assertThat(csv).contains(";2 d 2 h;8 h;");
        assertThat(lines(csv).get(1)).doesNotContain(",");
    }

    @Test
    void anEmptyReportIsJustTheHeader() {
        assertThat(lines(export(List.of()))).containsExactly(HEADER);
    }

    @Test
    void escape_quotesFieldsThatWouldBreakTheRow() {
        assertThat(CsvWriter.escape("Pérez; \"Tito\"")).isEqualTo("\"Pérez; \"\"Tito\"\"\"");
        assertThat(CsvWriter.escape("línea\nnueva")).isEqualTo("\"línea\nnueva\"");
        assertThat(CsvWriter.escape(null)).isEmpty();
    }

    @Test
    void escape_neutralizesFormulas() {
        assertThat(CsvWriter.escape("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(CsvWriter.escape("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    }

    private String export(List<ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow> rows) {
        return new String(exporter.export(augustReport(rows)), StandardCharsets.UTF_8);
    }

    /** Drops the BOM and the "sep=" declaration, leaving the header and the rows. */
    private static List<String> lines(String csv) {
        List<String> all = List.of(csv.substring(1).split("\r\n"));
        return all.subList(1, all.size());
    }
}
