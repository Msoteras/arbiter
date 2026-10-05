package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.reports.config.RequesterContext;
import ar.edu.utn.frba.arbiter.reports.dto.ReportBranding;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.MetaStripBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.SignatureBlock;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

final class ReportChrome {

    private static final DateTimeFormatter CODE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private ReportChrome() {
    }

    static String code(String kind, Instant generatedAt, ZoneId zone) {
        return "ARB-%s-%s".formatted(kind, CODE_STAMP.withZone(zone).format(generatedAt));
    }

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
