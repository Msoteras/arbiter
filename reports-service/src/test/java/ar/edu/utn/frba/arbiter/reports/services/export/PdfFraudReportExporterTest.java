package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.stream.LongStream;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.CLOCK;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.flaggedRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.lowScoreRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.septemberFraudReport;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.unscoredRow;
import static org.assertj.core.api.Assertions.assertThat;

/** Reads the generated PDF back as text: what matters is what a person sees on the page. */
class PdfFraudReportExporterTest {

    private final PdfFraudReportExporter exporter = new PdfFraudReportExporter(CLOCK);

    @Test
    void writesTheTitleThePeriodAndTheRows() throws IOException {
        Rendered pdf = render(List.of(flaggedRow(1482)));

        assertThat(pdf.pages()).isEqualTo(1);
        assertThat(pdf.text()).contains(
                "Reporte de detección de fraude",
                "Período: 01/09/2026 al 30/09/2026",
                "Ramo: Todos",
                "Nivel de alerta: Todos",
                "Generado el 11/09/2026 12:00",
                "Marcos Aguirre",
                "28.904.115",
                "Crítico",
                "Derivado a peritaje",
                "Página 1 de 1");
    }

    /**
     * The document leaves the insurer on its own, with nobody next to it to say that a flagged case
     * is not a determined one. So the caveat travels with it.
     */
    @Test
    void carriesTheCaveatThatItFlagsRatherThanDetermines() throws IOException {
        Rendered pdf = render(List.of(flaggedRow(1482)));

        assertThat(pdf.text()).contains("El sistema no determina fraude");
    }

    /**
     * The three columns a reader acts on have to fit whole. Only "Indicadores" may be cut, and the
     * widths are hand-tuned, so a truncated status or determination would go unnoticed otherwise.
     */
    @Test
    void theStatusAndTheDeterminationFitWithoutBeingCut() throws IOException {
        Rendered pdf = render(List.of(flaggedRow(1), unscoredRow(2)));

        assertThat(pdf.text())
                .contains("Derivado a peritaje")
                .contains("Sí · con respaldo pericial")
                .doesNotContain("Derivado a p…");
    }

    @Test
    void writesTheSummaryAboveTheTable() throws IOException {
        Rendered pdf = render(List.of(flaggedRow(1), flaggedRow(2), unscoredRow(3)));

        // 3 flagged and 1 determined out of the 20 claims the period had: the rate never travels
        // without the population it was taken from.
        assertThat(pdf.text()).contains(
                "Total: 3 de 20 denuncias con indicios (15%)",
                "Con señales cruzadas: 2",
                "Fraude determinado: 1 (5% del período, 1 con respaldo pericial)",
                "Por nivel de alerta: Crítico 2 · Sin evaluar 1",
                "Por señal (un expediente puede tener más de una):");
    }

    /** An empty report still has to say what it looked for, or it can't be told from any other. */
    @Test
    void anEmptyReportSaysSo_andStillNamesItsFilters() throws IOException {
        Rendered pdf = render(septemberFraudReport(List.of(), "Celulares", RiskBand.HIGH));

        assertThat(pdf.text()).contains(
                "Ramo: Celulares",
                "Nivel de alerta: Alto",
                "Ningún expediente con indicios en el período.");
    }

    /** With claims in the period, "none flagged" is a finding, and it keeps its population. */
    @Test
    void anEmptyReportOverAPeriodWithClaims_statesHowManyItLookedAt() throws IOException {
        Rendered pdf = render(septemberFraudReport(List.of(), null, null, 84));

        assertThat(pdf.text()).contains("Total: Ninguna de las 84 denuncias del período con indicios");
    }

    /** Same figure the preview shows, which formats the rate with one decimal. */
    @Test
    void theRatesKeepOneDecimal() throws IOException {
        Rendered pdf = render(septemberFraudReport(
                List.of(flaggedRow(1), flaggedRow(2), unscoredRow(3)), null, null, 84));

        assertThat(pdf.text()).contains(
                "Total: 3 de 84 denuncias con indicios (3,6%)",
                "Fraude determinado: 1 (1,2% del período");
    }

    /**
     * A low score is not an alert, so the page says the score did not flag the case instead of
     * printing "Bajo" under a column headed "Alerta".
     */
    @Test
    void aLowScoringCase_readsAsNotAlerted_ratherThanAsItsBand() throws IOException {
        Rendered pdf = render(List.of(lowScoreRow(1455)));

        assertThat(pdf.text())
                .contains("No alertó")
                .contains("Por nivel de alerta: No alertó 1")
                .doesNotContain("Bajo");
    }

    @Test
    void aLongReportBreaksPagesAndNumbersThem() throws IOException {
        List<FraudReportRow> rows = LongStream.range(1000, 1090).mapToObj(id -> flaggedRow(id)).toList();

        Rendered pdf = render(rows);

        assertThat(pdf.pages()).isGreaterThanOrEqualTo(3);
        assertThat(pdf.text()).contains(
                "Página 1 de " + pdf.pages(),
                "Página " + pdf.pages() + " de " + pdf.pages(),
                "1000",
                "1089");
    }

    @Test
    void aCharacterTheFontCantEncode_becomesAQuestionMarkInsteadOfFailingTheFile() throws IOException {
        Rendered pdf = render(List.of(flaggedRow(42, "Ana 😀 Pérez")));

        assertThat(pdf.text()).contains("Ana ? Pérez");
    }

    private Rendered render(List<FraudReportRow> rows) throws IOException {
        return render(septemberFraudReport(rows));
    }

    private Rendered render(FraudReport report) throws IOException {
        byte[] bytes = exporter.export(report);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new Rendered(document.getNumberOfPages(), new PDFTextStripper().getText(document));
        }
    }

    private record Rendered(int pages, String text) {}
}
