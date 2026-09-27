package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.reports.config.RequesterContext;
import ar.edu.utn.frba.arbiter.reports.dto.ReportBranding;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionSummary;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.BBVA;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.CLOCK;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.approvedRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.augustReport;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.brandedAs;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.fastTrackRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.lapsedRow;
import static org.assertj.core.api.Assertions.assertThat;

/** Reads the generated PDF back as text: what matters is what a person sees on the page. */
class PdfResolutionReportExporterTest {

    private final PdfResolutionReportExporter exporter =
            new PdfResolutionReportExporter(CLOCK, brandedAs(BBVA));

    @AfterEach
    void clearTheRequester() {
        RequesterContext.clear();
    }

    @Test
    void writesTheTitleThePeriodAndTheRows() throws IOException {
        Rendered pdf = render(List.of(approvedRow(42)));

        assertThat(pdf.pages()).isEqualTo(2);
        assertThat(pdf.text()).contains(
                "Reporte de resolución de siniestros",
                "Período 01/08/2026 — 31/08/2026",
                "Todos",
                "Ana Pérez",
                "DNI 30.111.222",
                "2 d 2 h",
                "Recomienda aprobar",
                "Aprobado",
                "Página 1 de 2");
    }

    /** The aggregates go above the detail, each one saying what it was counted over. */
    @Test
    void theHeadlineFiguresCarryWhatTheyAreCountedOver() throws IOException {
        Rendered pdf = render(List.of(approvedRow(1), fastTrackRow(2), lapsedRow(3)));

        assertThat(pdf.text()).contains(
                "EXPEDIENTES RESUELTOS",
                "2 aprobados · 1 caducado",
                // Averaged over the 2 decided cases; the lapsed one is listed, not averaged.
                "TIEMPO PROMEDIO",
                "1 d 2 h",
                "mediana 1 d 2 h · máx. 2 d 2 h",
                "RESUELTOS POR FAST TRACK",
                "33%",
                "1 de 3");
    }

    @Test
    void theDistributionsAreListedWithTheirShare() throws IOException {
        Rendered pdf = render(List.of(approvedRow(1), fastTrackRow(2), lapsedRow(3)));

        assertThat(pdf.text()).contains(
                "POR ESTADO FINAL", "Aprobado", "2 · 67%", "Caducado", "1 · 33%",
                "POR TIPO DE SINIESTRO", "Hurto", "Robo en vía pública");
    }

    /** Each run is its own document, and the code is what somebody quotes when asking about it. */
    @Test
    void everyPageCarriesTheReportCode() throws IOException {
        List<ResolutionReportRow> rows =
                LongStream.range(1000, 1060).mapToObj(id -> approvedRow(id)).toList();

        List<String> pages = pageTexts(augustReport(rows));

        assertThat(pages).hasSizeGreaterThan(1)
                .allSatisfy(page -> assertThat(page).contains("ARB-RES-20260911-1200"));
    }

    @Test
    void namesWhoAskedForTheReport() throws IOException {
        RequesterContext.set(new RequesterContext.Requester("Lucía Sánchez", "REFERENTE_ASEGURADORA"));

        Rendered pdf = render(List.of(approvedRow(42)));

        assertThat(pdf.text()).contains("SOLICITADO POR", "Lucía Sánchez",
                "Referente de la aseguradora");
    }

    /** A service token carries no name; the report still goes out. */
    @Test
    void withoutARequester_theCellSaysSoInsteadOfBeingBlank() throws IOException {
        Rendered pdf = render(List.of(approvedRow(42)));

        assertThat(pdf.text()).contains("SOLICITADO POR", "—");
    }

    @Test
    void theHeaderNamesTheInsurerAndAttributesArbiter() throws IOException {
        Rendered pdf = render(List.of(approvedRow(42)));

        assertThat(pdf.text()).contains("BBVA Seguros Argentina S.A.", "Generado con", "Arbiter");
    }

    /** Pages get printed, split and filed on their own, so not one of them may be anonymous. */
    @Test
    void everyPageCarriesTheInsurer() throws IOException {
        List<ResolutionReportRow> rows =
                LongStream.range(1000, 1060).mapToObj(id -> approvedRow(id)).toList();

        List<String> pages = pageTexts(augustReport(rows));

        assertThat(pages).hasSizeGreaterThan(1)
                .allSatisfy(page -> assertThat(page).contains("BBVA Seguros"));
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

    /** Apartarse is the analyst's call; the report marks the row so it can be read, not judged. */
    @Test
    void marksTheDecisionsThatDepartedFromTheRecommendation() throws IOException {
        Rendered pdf = render(List.of(approvedRow(1), departedRow(7)));

        assertThat(pdf.text()).contains(
                "SIGUIÓ LA RECOMENDACIÓN",
                "1 de 2",
                "2 de 2 con recomendación · 1 desvío",
                "El expediente #7 se aprobó pese a que el sistema recomendaba lo contrario.");
    }

    @Test
    void withNoDepartures_theCardSaysSo() throws IOException {
        Rendered pdf = render(List.of(approvedRow(1)));

        assertThat(pdf.text()).contains("1 de 1 con recomendación · sin desvíos");
    }

    /** Fast Track has no recommendation to depart from, so there is nothing to compare it against. */
    @Test
    void withOnlyFastTrackRows_theAgreementCardHasNothingToCompare() throws IOException {
        Rendered pdf = render(List.of(fastTrackRow(1), fastTrackRow(2)));

        assertThat(pdf.text()).contains("SIGUIÓ LA RECOMENDACIÓN", "Sin datos",
                "ninguno de los 2 tuvo recomendación que seguir");
    }

    @Test
    void crossesWhatTheSystemSuggestedAgainstWhatTheAnalystDecided() throws IOException {
        Rendered pdf = render(List.of(approvedRow(1), fastTrackRow(2), lapsedRow(3)));

        assertThat(pdf.text()).contains(
                "Sistema y analista, cruzados", "APROBÓ", "RECHAZÓ", "SIN DECISIÓN",
                "Recomienda aprobar", "Fast Track", "Sin clasificación");
    }

    /** Nothing else on the page says what a tinted cell is claiming. */
    @Test
    void theCrossTabSaysWhatItsColoursMean() throws IOException {
        Rendered pdf = render(List.of(approvedRow(1), fastTrackRow(2), departedRow(7)));

        assertThat(pdf.text()).contains(
                "El analista decidió en el mismo sentido que la recomendación",
                "El analista decidió en sentido contrario a la recomendación",
                "No había recomendación que seguir o no seguir");
    }

    /**
     * The count that reads wrong without its denominator: six closed cases can still be "2 de 4"
     * when two of them were Fast Track.
     */
    @Test
    void theAgreementCardSaysWhatItsDenominatorIs() throws IOException {
        Rendered pdf = render(List.of(approvedRow(1), fastTrackRow(2), fastTrackRow(3),
                departedRow(7)));

        assertThat(pdf.text()).contains("1 de 2", "2 de 4 con recomendación · 1 desvío");
        assertThat(pdf.text()).contains(
                "Se cuenta solo sobre los expedientes con una recomendación que se puede seguir o no.");
    }

    /**
     * The document no longer carries the previous period. It still travels in the report, so nothing
     * from it may leak onto the page by accident.
     */
    @Test
    void theExportDoesNotMentionThePreviousPeriod() throws IOException {
        ResolutionSummary previous =
                new ResolutionSummary(6, 6, 1200.0, 200.0, 3, 0.5, List.of(), List.of());

        Rendered pdf = render(augustReport(
                List.of(approvedRow(1), fastTrackRow(2), lapsedRow(3)), null, null, previous));

        assertThat(pdf.text()).doesNotContain("período anterior");
    }

    /** Nobody decided anything in the period: no average, rather than one over the lapsed ones. */
    @Test
    void withOnlyLapsedCases_saysThereIsNoAverage() throws IOException {
        Rendered pdf = render(List.of(lapsedRow(1)));

        assertThat(pdf.text()).contains("Sin datos", "ningún expediente decidido en el período");
    }

    /** An empty report still has to say what it looked for, or it can't be told from any other. */
    @Test
    void anEmptyReportSaysSo_andStillNamesItsFilters() throws IOException {
        Rendered pdf = render(augustReport(List.of(), "Celulares", "Hurto"));

        assertThat(pdf.text()).contains(
                "Celulares",
                "Hurto",
                "No hay expedientes cerrados en el período con estos filtros.");
    }

    @Test
    void explainsWhatTheTermsMeanForSomebodyOutsideTheTool() throws IOException {
        Rendered pdf = render(List.of(approvedRow(42)));

        assertThat(pdf.text()).contains(
                "Cómo se produjo este informe",
                "Qué significa cada término",
                "Fast Track",
                "Espera de terceros",
                "Referente de la aseguradora · aclaración y fecha");
    }

    @Test
    void carriesItsConfidentialityOnEveryPage() throws IOException {
        List<ResolutionReportRow> rows =
                LongStream.range(1000, 1060).mapToObj(id -> approvedRow(id)).toList();

        List<String> pages = pageTexts(augustReport(rows));

        assertThat(pages).allSatisfy(page ->
                assertThat(page).contains("Documento confidencial · Uso interno de BBVA Seguros"));
    }

    @Test
    void aLongReportBreaksPagesAndNumbersThem() throws IOException {
        List<ResolutionReportRow> rows =
                LongStream.range(1000, 1090).mapToObj(id -> approvedRow(id)).toList();

        Rendered pdf = render(augustReport(rows));

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

    private Rendered render(List<ResolutionReportRow> rows) throws IOException {
        return render(augustReport(rows));
    }

    private Rendered render(ResolutionReport report) throws IOException {
        byte[] bytes = exporter.export(report);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            // Whitespace is collapsed: a card label or a note that wrapped on the page is still the
            // same sentence, and the assertions are about what it says, not where it broke.
            return new Rendered(document.getNumberOfPages(),
                    new PDFTextStripper().getText(document).replaceAll("\\s+", " "));
        }
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

    /** Approved although the model recommended against it. */
    private static ResolutionReportRow departedRow(long caseId) {
        ResolutionReportRow row = approvedRow(caseId);
        return new ResolutionReportRow(row.caseId(), row.insuredName(), row.insuredDni(),
                row.branch(), row.claimCause(), row.reportedAt(), row.resolvedAt(),
                row.totalMinutes(), row.waitingMinutes(), Classification.LLM_NO_RECOMIENDA_APROBAR,
                "APPROVE", CaseStatus.APPROVED, row.analystName());
    }

    private record Rendered(int pages, String text) {}
}
