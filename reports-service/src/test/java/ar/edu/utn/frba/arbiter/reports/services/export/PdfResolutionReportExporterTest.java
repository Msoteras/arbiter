package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.stream.LongStream;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.CLOCK;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.approvedRow;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.augustReport;
import static org.assertj.core.api.Assertions.assertThat;

/** Reads the generated PDF back as text: what matters is what a person sees on the page. */
class PdfResolutionReportExporterTest {

    private final PdfResolutionReportExporter exporter = new PdfResolutionReportExporter(CLOCK);

    @Test
    void writesTheTitleThePeriodAndTheRows() throws IOException {
        Rendered pdf = render(List.of(approvedRow(42)));

        assertThat(pdf.pages()).isEqualTo(1);
        assertThat(pdf.text()).contains(
                "Reporte de resolución de siniestros",
                "Período: 01/08/2026 al 31/08/2026",
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

    @Test
    void anEmptyReportSaysSo() throws IOException {
        Rendered pdf = render(List.of());

        assertThat(pdf.text()).contains("0 siniestros resueltos", "No hay siniestros resueltos en el período.");
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

    private Rendered render(List<ResolutionReportRow> rows) throws IOException {
        byte[] bytes = exporter.export(augustReport(rows));
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new Rendered(document.getNumberOfPages(), new PDFTextStripper().getText(document));
        }
    }

    private record Rendered(int pages, String text) {}
}
