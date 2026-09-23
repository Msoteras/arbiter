package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.CLOCK;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.documentInconsistentRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.flaggedRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.lowScoreRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.septemberFraudReport;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.unscoredRow;
import static org.assertj.core.api.Assertions.assertThat;

class CsvFraudReportExporterTest {

    private static final String HEADER = "Nº expediente;Asegurado;DNI;Ramo;Hecho generador;"
            + "Fecha de denuncia;Score de riesgo;Señales;Denuncias en 12 meses;"
            + "Imágenes con coincidencia;Estado;Fraude determinado";

    private final CsvFraudReportExporter exporter = new CsvFraudReportExporter(CLOCK);

    @Test
    void writesABomTheHeaderAndOneLinePerFlaggedCase_inLocalTime() {
        String csv = export(List.of(flaggedRow(1482)));

        assertThat(csv.charAt(0)).isEqualTo((char) 0xFEFF);
        assertThat(lines(csv)).containsExactly(
                HEADER,
                "1482;Marcos Aguirre;28.904.115;Celulares;Robo en vía pública;12/09/2026 06:20;"
                        + "Crítico;Score de riesgo alto · 2 imágenes con coincidencia;"
                        + "3;2;Derivado a peritaje;No");
    }

    /** "Sin evaluar" rather than "Bajo": the scoring never ran on that case. */
    @Test
    void anUnscoredCase_readsAsUnevaluated_andCarriesItsDetermination() {
        String csv = export(List.of(unscoredRow(1447)));

        assertThat(lines(csv).get(1)).isEqualTo(
                "1447;Romina Vega;34.771.009;Tecnología Portátil;Hurto;05/09/2026 11:00;Sin evaluar;"
                        + "1 imagen con coincidencia;1;1;Rechazado;Sí · con respaldo pericial");
    }

    /** "Bajo" never reaches the file: a low score is not an alert. */
    @Test
    void aLowScoringCase_readsAsNotAlerted_ratherThanAsItsBand() {
        String csv = export(List.of(lowScoreRow(1455)));

        assertThat(lines(csv).get(1)).contains(";No alertó;1 imagen con coincidencia;");
        assertThat(csv).doesNotContain("Bajo");
    }

    @Test
    void theDocumentSignal_printsItsRationaleInTheSignalsColumn() {
        String csv = export(List.of(documentInconsistentRow(24)));

        assertThat(lines(csv).get(1)).isEqualTo(
                "24;Nicolás Farías;31.204.556;Celulares;Hurto;16/09/2026 08:10;Sin evaluar;"
                        + "La constancia policial está fechada el 2026-09-14, pero el asegurado "
                        + "declaró haber denunciado el 2026-09-15;1;0;Pendiente de revisión;No");
    }

    @Test
    void anEmptyReportIsTheHeaderAlone_soItStillLoads() {
        assertThat(lines(export(List.of()))).containsExactly(HEADER);
    }

    private String export(List<FraudReportRow> rows) {
        return new String(exporter.export(septemberFraudReport(rows)), StandardCharsets.UTF_8);
    }

    private static List<String> lines(String csv) {
        return List.of(csv.substring(1).split("\r\n"));
    }
}
