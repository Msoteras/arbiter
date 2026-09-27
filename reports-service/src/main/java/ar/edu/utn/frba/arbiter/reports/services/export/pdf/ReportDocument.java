package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import ar.edu.utn.frba.arbiter.reports.dto.ReportBranding;

import java.util.List;

/**
 * An exported report, as the page furniture needs it plus the blocks that make up its body.
 *
 * <p>What every {@code Pdf*Exporter} produces and {@link PdfDocumentWriter} consumes: the exporters
 * decide what the report says, the writer decides where it lands on the page, and neither knows the
 * other's job.
 *
 * @param periodLabel what the running header repeats, e.g. "01/09/2026 — 20/09/2026"
 * @param code        the reference somebody quotes when asking about this particular run
 */
public record ReportDocument(
        String title,
        String periodLabel,
        String code,
        ReportBranding branding,
        String confidentialityNote,
        List<Block> blocks
) {}
