package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.reports.config.RequesterContext;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSummary;
import ar.edu.utn.frba.arbiter.reports.services.FraudSummaries;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.stream.LongStream;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.BBVA;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.CLOCK;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.brandedAs;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.documentInconsistentRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.flaggedRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.lowScoreRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.septemberFraudReport;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.unscoredRow;
import static org.assertj.core.api.Assertions.assertThat;

/** Reads the generated PDF back as text: what matters is what a person sees on the page. */
class PdfFraudReportExporterTest {

    private final PdfFraudReportExporter exporter =
            new PdfFraudReportExporter(CLOCK, brandedAs(BBVA));

    @AfterEach
    void clearTheRequester() {
        RequesterContext.clear();
    }

    @Test
    void writesTheTitleThePeriodAndTheRows() throws IOException {
        Rendered pdf = render(List.of(flaggedRow(1482)));

        assertThat(pdf.text()).contains(
                "Reporte de detección de fraude",
                "Período 01/09/2026 — 30/09/2026",
                "RAMO Todos",
                "SCORE DE RIESGO Todos",
                "Marcos Aguirre",
                "DNI 28.904.115",
                "Crítico",
                "Derivado a peritaje",
                "Página 1 de " + pdf.pages());
    }

    /** The report exists to say what it is not: an accusation. */
    @Test
    void opensBySayingThatTheSystemDoesNotDetermineFraud() throws IOException {
        Rendered pdf = render(List.of(flaggedRow(1482)));

        assertThat(pdf.text()).contains(
                "ALCANCE DE ESTE INFORME",
                "El sistema no determina fraude: señala indicios para revisión humana.");
    }

    @Test
    void theHeadlineFiguresCarryWhatTheyAreCountedOver() throws IOException {
        Rendered pdf = render(septemberFraudReport(
                List.of(flaggedRow(1), flaggedRow(2), unscoredRow(3)), null, null, 20));

        assertThat(pdf.text()).contains(
                "DENUNCIAS DEL PERÍODO", "20",
                "CON AL MENOS UNA SEÑAL", "15%", "3 de 20 denuncias",
                "CON DOS O MÁS SEÑALES", "2");
    }

    /** A period whose flagged cases are still open cannot report a fraud count yet. */
    @Test
    void withTheFlaggedCasesStillOpen_theFraudCardSaysThereIsNoDataYet() throws IOException {
        Rendered pdf = render(List.of(flaggedRow(1), flaggedRow(2), flaggedRow(3)));

        assertThat(pdf.text()).contains("FRAUDE DETERMINADO", "Sin datos", "siguen abiertas");
    }

    @Test
    void theDistributionsSayWhatTheyAreOverAndThatTheyOverlap() throws IOException {
        Rendered pdf = render(List.of(flaggedRow(1), flaggedRow(2), unscoredRow(3)));

        assertThat(pdf.text()).contains(
                "POR SCORE DE RIESGO", "Crítico", "Sin evaluar",
                "Sobre las denuncias señaladas, no sobre el total del período.",
                "POR SEÑAL DISPARADA",
                "Una denuncia puede disparar más de una señal, así que no suman 100%.");
    }

    /** Two signals don't fit one line: the cell wraps instead of ellipsizing the second one away. */
    @Test
    void theSignalsOfACase_areWrittenWhole_evenWhenTheyDoNotFitOneLine() throws IOException {
        Rendered pdf = render(List.of(flaggedRow(1482)));

        assertThat(pdf.text())
                .contains("Score de riesgo alto")
                .contains("2 imágenes con")
                .contains("coincidencia");
    }

    /**
     * The document signal carries its own rationale; a long one is still capped at two lines and
     * ellipsized (the CSV and the screen carry it whole).
     */
    @Test
    void theDocumentSignal_printsItsOwnRationale() throws IOException {
        Rendered pdf = render(List.of(documentInconsistentRow(24)));

        assertThat(pdf.text())
                .contains("La constancia policial está fechada el 2026-09-14")
                .contains("…");
    }

    /** "Fraude determinado" is left out of the comparison: it lags in both periods. */
    @Test
    void theComparisonLine_measuresAgainstThePreviousPeriod() throws IOException {
        List<FraudReportRow> rows = List.of(flaggedRow(1), flaggedRow(2), unscoredRow(3));
        FraudSummary previous = FraudSummaries.of(List.of(flaggedRow(10)), 16);

        Rendered pdf = render(septemberFraudReport(rows, null, null, 20, previous));

        assertThat(pdf.text()).contains(
                "Vs. período anterior de igual duración: 16 denuncias (+4) · "
                        + "Con al menos una señal: 6,3% (+8,8 pp) · Con dos o más señales: 1 (+1)");
    }

    /** Below the minimum base, the previous figures print but nothing claims a trend out of them. */
    @Test
    void theComparison_dropsTheDeltaWhenThePreviousPeriodIsTooThin() throws IOException {
        List<FraudReportRow> rows = List.of(flaggedRow(1), flaggedRow(2), unscoredRow(3));
        FraudSummary previous = FraudSummaries.of(List.of(flaggedRow(10)), 2);

        Rendered pdf = render(septemberFraudReport(rows, null, null, 20, previous));

        assertThat(pdf.text()).contains(
                "Vs. período anterior de igual duración: 2 denuncias · "
                        + "Con al menos una señal: 50% · Con dos o más señales: 1");
    }

    /** An empty report still has to say what it looked for, or it can't be told from any other. */
    @Test
    void anEmptyReportSaysSo_andStillNamesItsFilters() throws IOException {
        Rendered pdf = render(septemberFraudReport(List.of(), "Celulares", RiskBand.HIGH));

        assertThat(pdf.text()).contains(
                "RAMO Celulares",
                "SCORE DE RIESGO Alto",
                "Ninguna denuncia del período disparó una señal con estos filtros.");
    }

    /** "None flagged" is stated with its population. */
    @Test
    void anEmptyReportOverAPeriodWithClaims_statesHowManyItLookedAt() throws IOException {
        Rendered pdf = render(septemberFraudReport(List.of(), null, null, 84));

        assertThat(pdf.text()).contains("DENUNCIAS DEL PERÍODO 84", "0 de 84 denuncias");
    }

    /** One decimal, same as the preview. */
    @Test
    void theRatesKeepOneDecimal() throws IOException {
        Rendered pdf = render(septemberFraudReport(
                List.of(flaggedRow(1), flaggedRow(2), unscoredRow(3)), null, null, 84));

        assertThat(pdf.text()).contains("3,6%", "3 de 84 denuncias");
    }

    /** A low score prints as "did not alert", never as "Bajo". */
    @Test
    void aLowScoringCase_readsAsNotAlerted_ratherThanAsItsBand() throws IOException {
        Rendered pdf = render(List.of(lowScoreRow(1455)));

        assertThat(pdf.text())
                .contains("No alertó")
                .doesNotContain("Bajo");
    }

    /**
     * The header is the writer's, not this exporter's, so every report gets the same one — including
     * the ones nobody has written yet.
     */
    @Test
    void theHeaderNamesTheInsurerAndAttributesArbiter() throws IOException {
        Rendered pdf = render(List.of(flaggedRow(1482)));

        assertThat(pdf.text()).contains("BBVA Seguros Argentina S.A.", "Generado con", "Arbiter",
                "ARB-FRD-20260911-1200");
    }

    @Test
    void namesWhoAskedForTheReport() throws IOException {
        RequesterContext.set(new RequesterContext.Requester("Lucía Sánchez", "REFERENTE_ASEGURADORA"));

        Rendered pdf = render(List.of(flaggedRow(1482)));

        assertThat(pdf.text()).contains("SOLICITADO POR", "Lucía Sánchez",
                "Referente de la aseguradora");
    }

    @Test
    void explainsWhatTheTermsMeanForSomebodyOutsideTheTool() throws IOException {
        Rendered pdf = render(List.of(flaggedRow(1482)));

        assertThat(pdf.text()).contains(
                "Cómo se produjo este informe",
                "Qué significa cada término",
                "Score de riesgo",
                "Señales cruzadas",
                "Fraude determinado",
                "Referente de la aseguradora · aclaración y fecha");
    }

    @Test
    void aLongReportBreaksPagesAndNumbersThem() throws IOException {
        List<FraudReportRow> rows =
                LongStream.range(1000, 1090).mapToObj(id -> flaggedRow(id)).toList();

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
            // Whitespace is collapsed: a card label or a note that wrapped on the page is still the
            // same sentence, and the assertions are about what it says, not where it broke.
            return new Rendered(document.getNumberOfPages(),
                    new PDFTextStripper().getText(document).replaceAll("\\s+", " "));
        }
    }

    private record Rendered(int pages, String text) {}
}
