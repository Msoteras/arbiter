package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSummary;
import ar.edu.utn.frba.arbiter.reports.services.FraudSummaries;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.stream.LongStream;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.CLOCK;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.documentInconsistentRow;
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
                "Score de riesgo: Todos",
                "Generado el 11/09/2026 12:00",
                "Marcos Aguirre",
                "28.904.115",
                "Crítico",
                "Derivado a peritaje",
                "Página 1 de 1");
    }

    /**
     * Every column a reader acts on has to fit on its own line: the widths are hand-tuned, and a
     * status or a determination wrapping in half would go unnoticed.
     */
    @Test
    void theStatusAndTheDeterminationFitWithoutBeingCut() throws IOException {
        Rendered pdf = render(List.of(flaggedRow(1), unscoredRow(2)));

        assertThat(pdf.text())
                .contains("Derivado a peritaje")
                .contains("Sí · con respaldo pericial")
                .doesNotContain("Derivado a p…");
    }

    /**
     * Two signals don't fit one line of the column, and the coincidence of signals is the whole
     * finding: the cell wraps instead of ellipsizing the second one away.
     *
     * <p>Asserted in halves because the wrap lands inside the second signal and the line break is
     * not what this is about — matching the phrase whole would make the test fail the next time a
     * column width moves, which is exactly the kind of change it should survive.
     */
    @Test
    void theSignalsOfACase_areWrittenWhole_evenWhenTheyDoNotFitOneLine() throws IOException {
        Rendered pdf = render(List.of(flaggedRow(1482)));

        // Whitespace normalized: the point is that nothing was cut, and where the cell wraps is the
        // layout's business — with two signals the break falls in the middle of the second phrase.
        assertThat(pdf.text().replaceAll("\\s+", " "))
                .contains("Score de riesgo alto")
                .contains("2 imágenes con")
                .contains("coincidencia")
                .doesNotContain("…");
    }

    /**
     * The document signal carries its own rationale instead of a generic label — the same reason
     * the image signal carries a count: what to look at, not just that something was flagged. A
     * rationale this long still hits the column's own two-line cap (see MAX_CELL_LINES) and gets
     * ellipsized like any other cell — the CSV and the screen are where it travels whole.
     */
    @Test
    void theDocumentSignal_printsItsOwnRationale() throws IOException {
        Rendered pdf = render(List.of(documentInconsistentRow(24)));

        assertThat(pdf.text().replaceAll("\\s+", " "))
                .contains("La constancia policial está fechada el 2026-09-14")
                .contains("…");
        assertThat(pdf.text()).contains("Por señal (una denuncia puede tener más de una):");
    }

    @Test
    void writesTheSummaryAboveTheTable() throws IOException {
        Rendered pdf = render(List.of(flaggedRow(1), flaggedRow(2), unscoredRow(3)));

        // 3 flagged and 1 determined out of the 20 claims the period had: the rate never travels
        // without the population it was taken from.
        assertThat(pdf.text()).contains(
                "Total: 3 de 20 denuncias con al menos una señal (15%)",
                "Con dos o más señales: 2",
                "Fraude determinado: 1 (5% del período, 1 con respaldo pericial)",
                "Por score de riesgo: Crítico 2 · Sin evaluar 1",
                "Por señal (una denuncia puede tener más de una):");
    }

    /** Fraude determinado is left out of the comparison — see the exporter's javadoc on why. */
    @Test
    void theSummaryLine_comparesAgainstThePreviousPeriod() throws IOException {
        List<FraudReportRow> rows = List.of(flaggedRow(1), flaggedRow(2), unscoredRow(3));
        FraudSummary previous = FraudSummaries.of(List.of(flaggedRow(10)), 16);

        Rendered pdf = render(septemberFraudReport(rows, null, null, 20, previous));

        assertThat(pdf.text()).contains(
                "Vs. período anterior: 16 denuncias (+4) · Con al menos una señal: 6,3% (+8,8 pp) · "
                        + "Con dos o más señales: 1 (+1)");
    }

    /** Below the minimum base, the previous figures print but nothing claims a trend out of them. */
    @Test
    void theComparison_dropsTheDeltaWhenThePreviousPeriodIsTooThin() throws IOException {
        List<FraudReportRow> rows = List.of(flaggedRow(1), flaggedRow(2), unscoredRow(3));
        FraudSummary previous = FraudSummaries.of(List.of(flaggedRow(10)), 2);

        Rendered pdf = render(septemberFraudReport(rows, null, null, 20, previous));

        assertThat(pdf.text()).contains(
                "Vs. período anterior: 2 denuncias · Con al menos una señal: 50% · Con dos o más señales: 1");
    }

    /** An empty report still has to say what it looked for, or it can't be told from any other. */
    @Test
    void anEmptyReportSaysSo_andStillNamesItsFilters() throws IOException {
        Rendered pdf = render(septemberFraudReport(List.of(), "Celulares", RiskBand.HIGH));

        assertThat(pdf.text()).contains(
                "Ramo: Celulares",
                "Score de riesgo: Alto",
                "Ninguna denuncia con señales en el período.");
    }

    /** With claims in the period, "none flagged" is a finding, and it keeps its population. */
    @Test
    void anEmptyReportOverAPeriodWithClaims_statesHowManyItLookedAt() throws IOException {
        Rendered pdf = render(septemberFraudReport(List.of(), null, null, 84));

        assertThat(pdf.text()).contains("Total: Ninguna de las 84 denuncias del período con señales");
    }

    /** Same figure the preview shows, which formats the rate with one decimal. */
    @Test
    void theRatesKeepOneDecimal() throws IOException {
        Rendered pdf = render(septemberFraudReport(
                List.of(flaggedRow(1), flaggedRow(2), unscoredRow(3)), null, null, 84));

        assertThat(pdf.text()).contains(
                "Total: 3 de 84 denuncias con al menos una señal (3,6%)",
                "Fraude determinado: 1 (1,2% del período");
    }

    /**
     * A low score is not an alert, so the page says the score did not flag the case instead of
     * printing "Bajo" under a column headed "Score".
     */
    @Test
    void aLowScoringCase_readsAsNotAlerted_ratherThanAsItsBand() throws IOException {
        Rendered pdf = render(List.of(lowScoreRow(1455)));

        assertThat(pdf.text())
                .contains("No alertó")
                .contains("Por score de riesgo: No alertó 1")
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
