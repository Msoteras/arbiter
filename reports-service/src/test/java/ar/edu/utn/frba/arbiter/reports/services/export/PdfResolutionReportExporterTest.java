package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.reports.dto.ReportBranding;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionSummary;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.BBVA;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.CLOCK;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.approvedRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.brandedAs;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.augustReport;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.fastTrackRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.lapsedRow;
import static org.assertj.core.api.Assertions.assertThat;

/** Reads the generated PDF back as text: what matters is what a person sees on the page. */
class PdfResolutionReportExporterTest {

    private final PdfResolutionReportExporter exporter =
            new PdfResolutionReportExporter(CLOCK, brandedAs(BBVA));

    @Test
    void writesTheTitleThePeriodAndTheRows() throws IOException {
        Rendered pdf = render(List.of(approvedRow(42)));

        assertThat(pdf.pages()).isEqualTo(1);
        assertThat(pdf.text()).contains(
                "Reporte de resolución de siniestros",
                "Período: 01/08/2026 al 31/08/2026",
                "Ramo: Todos",
                "Tipo de siniestro: Todos",
                "1 siniestro resuelto",
                "Generado el 11/09/2026 12:00",
                "Ana Pérez",
                "30.111.222",
                "2 d 2 h",
                "Recomienda aprobar",
                "Aprobado",
                "Página 1 de 1");
    }

    /** The aggregates go on the first page, before the detail. */
    @Test
    void writesTheSummaryAboveTheTable() throws IOException {
        Rendered pdf = render(List.of(approvedRow(1), fastTrackRow(2), lapsedRow(3)));

        // Averaged over the 2 decided cases (the lapsed one is listed, not averaged), split into
        // handling time and waiting on third parties.
        assertThat(pdf.text()).contains(
                "Total: 3 siniestros resueltos",
                "Tiempo promedio de resolución: 1 d 2 h sobre 2 decididos "
                        + "(22 h 15 min de gestión · 4 h esperando a terceros)",
                "Fast Track: 1 (33%)",
                "Por estado: Aprobado 2 · Caducado 1",
                "Por tipo de siniestro: Hurto 2 · Robo en vía pública 1");
    }

    /** A heading line longer than the page wraps instead of being cut. */
    @Test
    void aLongDistributionWrapsInsteadOfBeingCut() throws IOException {
        List<ResolutionReportRow> rows = LongStream.rangeClosed(1, 12)
                .mapToObj(id -> withClaimCause(approvedRow(id),
                        "Daño por granizo sobre el bien asegurado número " + id))
                .toList();

        Rendered pdf = render(rows);

        // The table cuts this column, so the full names can only come from the wrapped heading.
        String text = pdf.text().replaceAll("\\s+", " ");
        LongStream.rangeClosed(1, 12).forEach(id ->
                assertThat(text).contains("Daño por granizo sobre el bien asegurado número " + id + " 1"));
    }

    @Test
    void theSummaryLine_comparesAgainstThePreviousPeriod() throws IOException {
        ResolutionSummary previous =
                new ResolutionSummary(6, 6, 1200.0, 200.0, 3, 0.5, List.of(), List.of());

        Rendered pdf = render(augustReport(
                List.of(approvedRow(1), fastTrackRow(2), lapsedRow(3)), null, null, previous));

        assertThat(pdf.text()).contains(
                "Vs. período anterior: 6 siniestros resueltos (-3) · Tiempo promedio: 20 h "
                        + "(+6 h 15 min) · Fast Track: 50% (-16,7 pp)");
    }

    /** Below the minimum base, the previous figures print but nothing claims a trend out of them. */
    @Test
    void theComparison_dropsTheDeltaWhenThePreviousPeriodIsTooThin() throws IOException {
        ResolutionSummary previous =
                new ResolutionSummary(3, 3, 1200.0, 200.0, 1, 0.5, List.of(), List.of());

        Rendered pdf = render(augustReport(
                List.of(approvedRow(1), fastTrackRow(2), lapsedRow(3)), null, null, previous));

        assertThat(pdf.text()).contains(
                "Vs. período anterior: 3 siniestros resueltos · Tiempo promedio: 20 h · Fast Track: 50%");
    }

    /** Nobody decided anything in the period: no average, rather than one over the lapsed ones. */
    @Test
    void withOnlyLapsedCases_saysThereIsNoAverage() throws IOException {
        Rendered pdf = render(List.of(lapsedRow(1)));

        assertThat(pdf.text()).contains("Ningún expediente decidido: sin tiempo promedio");
    }

    /** An empty report still has to say what it looked for, or it can't be told from any other. */
    @Test
    void anEmptyReportSaysSo_andStillNamesItsFilters() throws IOException {
        Rendered pdf = render(augustReport(List.of(), "Celulares", "Hurto"));

        assertThat(pdf.text()).contains(
                "Ramo: Celulares",
                "Tipo de siniestro: Hurto",
                "No hay siniestros resueltos en el período.");
    }

    @Test
    void aLongReportBreaksPagesAndNumbersThem() throws IOException {
        List<ResolutionReportRow> rows = LongStream.range(1000, 1090).mapToObj(id -> approvedRow(id)).toList();

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
        Rendered pdf = render(List.of(approvedRow(42, "Ana 😀 Pérez")));

        assertThat(pdf.text()).contains("Ana ? Pérez");
    }

    @Test
    void theHeaderNamesTheInsurerAndAttributesArbiter() throws IOException {
        Rendered pdf = render(List.of(approvedRow(42)));

        assertThat(pdf.text()).contains("BBVA Seguros Argentina S.A.", "Generado con", "Arbiter");
    }

    /** Pages get printed, split and filed on their own, so not one of them may be anonymous. */
    @Test
    void everyPageCarriesTheInsurer() throws IOException {
        List<ResolutionReportRow> rows = LongStream.range(1000, 1090).mapToObj(id -> approvedRow(id)).toList();

        List<String> pages = pageTexts(augustReport(rows));

        assertThat(pages).hasSizeGreaterThanOrEqualTo(3)
                .allSatisfy(page -> assertThat(page).contains("BBVA Seguros Argentina S.A."));
    }

    /** The identity sits on top of the figures: losing it must not cost the referent the export. */
    @Test
    void withoutAnInsurer_theHeaderFallsBackInsteadOfFailingTheExport() throws IOException {
        byte[] bytes = new PdfResolutionReportExporter(CLOCK, brandedAs(ReportBranding.UNKNOWN))
                .export(augustReport(List.of(approvedRow(42))));

        try (PDDocument document = Loader.loadPDF(bytes)) {
            assertThat(new PDFTextStripper().getText(document))
                    .contains("Aseguradora", "Reporte de resolución de siniestros", "Ana Pérez");
        }
    }

    private Rendered render(List<ResolutionReportRow> rows) throws IOException {
        return render(augustReport(rows));
    }

    private List<String> pageTexts(ResolutionReport report) throws IOException {
        byte[] bytes = exporter.export(report);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            List<String> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document));
            }
            return pages;
        }
    }

    private Rendered render(ResolutionReport report) throws IOException {
        byte[] bytes = exporter.export(report);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new Rendered(document.getNumberOfPages(), new PDFTextStripper().getText(document));
        }
    }

    private static ResolutionReportRow withClaimCause(ResolutionReportRow row, String claimCause) {
        return new ResolutionReportRow(row.caseId(), row.insuredName(), row.insuredDni(), row.branch(),
                claimCause, row.reportedAt(), row.resolvedAt(), row.totalMinutes(), row.waitingMinutes(),
                row.classification(), row.analystDecision(), row.finalStatus(), row.analystName());
    }

    private record Rendered(int pages, String text) {}
}
