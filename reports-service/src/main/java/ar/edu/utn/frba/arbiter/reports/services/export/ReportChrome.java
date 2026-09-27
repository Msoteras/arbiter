package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.reports.config.RequesterContext;
import ar.edu.utn.frba.arbiter.reports.dto.ReportBranding;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.MetaStripBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.SignatureBlock;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The parts of an exported report that do not depend on which report it is: who asked for it, the
 * code it is filed under, the confidentiality it carries and the lines it gets signed on.
 *
 * <p>Shared so the two reports cannot drift apart on any of it. A referent who files both should not
 * have to notice that one names the requester and the other does not.
 */
final class ReportChrome {

    private static final DateTimeFormatter CODE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private ReportChrome() {
    }

    /**
     * Derived from the moment it ran rather than stored: two runs of the same filters are different
     * documents, and the one on somebody's desk has to be identifiable without a table of its own.
     *
     * @param kind the report's three-letter family, e.g. {@code RES} or {@code FRD}
     */
    static String code(String kind, Instant generatedAt, ZoneId zone) {
        return "ARB-%s-%s".formatted(kind, CODE_STAMP.withZone(zone).format(generatedAt));
    }

    /** Falls back to the role alone, and then to a dash: a report is never blocked on a missing name. */
    static MetaStripBlock.Cell requestedBy() {
        RequesterContext.Requester requester = RequesterContext.get();
        if (requester == null) {
            return new MetaStripBlock.Cell("Solicitado por", "—", null);
        }
        String role = requester.role() == null ? null : ReportLabels.role(requester.role());
        return new MetaStripBlock.Cell("Solicitado por",
                requester.name() == null ? (role == null ? "—" : role) : requester.name(),
                requester.name() == null ? null : role);
    }

    static MetaStripBlock.Cell issuedAt(Instant generatedAt, ZoneId zone) {
        return MetaStripBlock.Cell.of("Emitido",
                ReportLabels.DATE_TIME.withZone(zone).format(generatedAt) + " h");
    }

    /** The strip that closes the document, repeating what an archived copy has to carry on its own. */
    static MetaStripBlock provenance(String code, Instant generatedAt, ZoneId zone) {
        RequesterContext.Requester requester = RequesterContext.get();
        return new MetaStripBlock(List.of(
                MetaStripBlock.Cell.of("Sistema de origen", "Arbiter · Reportes"),
                MetaStripBlock.Cell.of("Emitido por",
                        requester == null || requester.name() == null ? "—" : requester.name()),
                MetaStripBlock.Cell.of("Generado",
                        ReportLabels.DATE_TIME.withZone(zone).format(generatedAt) + " h"),
                MetaStripBlock.Cell.of("Código del reporte", code)));
    }

    /**
     * Arbiter records who generated the document, not who signed it off leaving the company. That is
     * a signature, so the sheet leaves room for one.
     */
    static SignatureBlock signatures() {
        return new SignatureBlock(List.of(
                "Referente de la aseguradora · aclaración y fecha",
                "Gerencia de Siniestros · aclaración y fecha"));
    }

    static String confidentiality(ReportBranding branding) {
        return "Documento confidencial · Uso interno de %s. Contiene datos personales protegidos por "
                .formatted(branding.insurerName())
                + "la normativa vigente. No difundir fuera de la compañía.";
    }
}
