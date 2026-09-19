package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.CLOCK;
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

    /**
     * "Sin evaluar" and not "Bajo": the scoring never ran on that case, which is a different thing
     * from having run and come out low. The determination is the analyst's and says so.
     */
    @Test
    void anUnscoredCase_readsAsUnevaluated_andCarriesItsDetermination() {
        String csv = export(List.of(unscoredRow(1447)));

        assertThat(lines(csv).get(1)).isEqualTo(
                "1447;Romina Vega;34.771.009;Tecnología Portátil;Hurto;05/09/2026 11:00;Sin evaluar;"
                        + "1 imagen con coincidencia;1;1;Rechazado;Sí · con respaldo pericial");
    }

    /** "Bajo" never reaches the file: a low score is not an alert, and the signals say why it is here. */
    @Test
    void aLowScoringCase_readsAsNotAlerted_ratherThanAsItsBand() {
        String csv = export(List.of(lowScoreRow(1455)));

        assertThat(lines(csv).get(1)).contains(";No alertó;1 imagen con coincidencia;");
        assertThat(csv).doesNotContain("Bajo");
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
