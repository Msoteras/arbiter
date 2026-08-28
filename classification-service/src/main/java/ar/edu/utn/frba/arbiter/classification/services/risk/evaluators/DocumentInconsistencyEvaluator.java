package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Contradictions between what the attached documents say and what the claim says (H0012).
 *
 * <p>It was a stub until 10/08 because OCR returned <b>free text</b>: comparing paragraphs doesn't
 * give a deterministic result, and this factor has to be one. What unblocked it was the extraction
 * pass starting to return the data as <b>typed fields</b> ({@link DocumentExtraction.Fields}) — the
 * model reads, the code compares (D4b, same pattern as D4a).
 *
 * <p><b>A missing field is never an inconsistency.</b> A police certificate carries no IMEI and a
 * photo of the device carries no amount: null means "the document doesn't say", and confusing it
 * with "doesn't match" would turn every incomplete attachment into a fraud suspicion.
 *
 * <p>With no documents examined the factor is declared <b>not evaluable</b> instead of contributing
 * 0.0: a case with no attachments analyzed isn't a consistent case, it's one we know nothing about,
 * and a 0.0 would lower everyone's score.
 */
@Component
public class DocumentInconsistencyEvaluator implements RiskFactorEvaluator {

    /** Tolerated gap between the document's amount and the claimed one: rounding, VAT, shipping. */
    private static final BigDecimal AMOUNT_TOLERANCE_RATIO = new BigDecimal("0.10");

    /** A document dated more than a week before the event is no longer "of the event". */
    private static final int DOCUMENT_DATE_TOLERANCE_DAYS = 7;

    /** Each contradiction found adds this; two of them already saturate the factor. */
    private static final double SCORE_PER_FINDING = 0.5;

    /** Attachment type of the police certificate, the same the document schedule uses. */
    private static final String POLICE_REPORT_TYPE = "police_report";

    @Override
    public String factorId() {
        return RiskFactorIds.DOCUMENT_INCONSISTENCY;
    }

    @Override
    public Contribution evaluate(RiskContext context) {
        Map<String, DocumentExtraction> documents = context.documents();
        if (documents.isEmpty()) {
            return Contribution.notEvaluable(factorId(),
                    "No se analizó documentación en este expediente — factor no evaluable");
        }

        List<String> findings = new ArrayList<>();
        documents.forEach((type, extraction) -> {
            checkImei(context, type, extraction.fields(), findings);
            checkDocumentDate(context, type, extraction.fields(), findings);
            checkAmount(context, type, extraction.fields(), findings);
        });
        checkDeclaredPoliceReportDate(context, documents, findings);

        if (findings.isEmpty()) {
            return new Contribution(factorId(), 0.0,
                    "Los datos de los documentos coinciden con los del siniestro");
        }
        double score = Math.min(1.0, findings.size() * SCORE_PER_FINDING);
        return new Contribution(factorId(), score, String.join(" · ", findings));
    }

    /**
     * The cross-check that motivated the factor: the IMEI on the document against the insured
     * item's. It only runs if the policy has an IMEI (Celulares branch); in Tecnología Portátil
     * there's nothing to compare against and the check doesn't take part.
     */
    private void checkImei(
            RiskContext context, String type, DocumentExtraction.Fields fields, List<String> findings) {
        String insuredImei = context.policy() == null ? null : context.policy().imei();
        if (insuredImei == null || fields.imei() == null) {
            return;
        }
        if (!insuredImei.equals(fields.imei())) {
            findings.add(String.format(
                    "El IMEI del documento '%s' (%s) no coincide con el del bien asegurado (%s)",
                    type, fields.imei(), insuredImei));
        }
    }

    /**
     * The document's date can't precede the event: a repair invoice or a police certificate are
     * issued afterwards. A week backwards is tolerated for the legitimate case of the device's
     * purchase invoice, which is genuinely earlier.
     */
    private void checkDocumentDate(
            RiskContext context, String type, DocumentExtraction.Fields fields, List<String> findings) {
        if (fields.documentDate() == null || context.claim() == null || context.claim().eventDate() == null) {
            return;
        }
        LocalDate eventDate = context.claim().eventDate().toLocalDate();
        LocalDate earliestAccepted = eventDate.minusDays(DOCUMENT_DATE_TOLERANCE_DAYS);
        if (fields.documentDate().isBefore(earliestAccepted)) {
            findings.add(String.format(
                    "El documento '%s' está fechado el %s, anterior al hecho (%s)",
                    type, fields.documentDate(), eventDate));
        }
    }

    /**
     * D12 · what the insured <b>declared</b> about their police report against what the certificate
     * says. It's the signal that justifies storing both dates separately: reporting late is one
     * thing (the deadline rule evaluates that), declaring a date the paper doesn't back is another.
     *
     * <p>Compared by day and not by hour: the insured declares an exact time, the certificate
     * usually doesn't, and demanding a match to the minute would make anyone who rounded look
     * suspicious.
     */
    private void checkDeclaredPoliceReportDate(
            RiskContext context, Map<String, DocumentExtraction> documents, List<String> findings) {
        DocumentExtraction policeReport = documents.get(POLICE_REPORT_TYPE);
        if (policeReport == null || context.claim() == null || context.claim().policeReportAt() == null) {
            return;
        }
        LocalDate onPaper = policeReport.fields().documentDate();
        if (onPaper == null) {
            return;
        }
        LocalDate declared = context.claim().policeReportAt().toLocalDate();
        if (!onPaper.equals(declared)) {
            findings.add(String.format(
                    "La constancia policial está fechada el %s, pero el asegurado declaró haber denunciado el %s",
                    onPaper, declared));
        }
    }

    /** The document's amount against the claimed one, tolerating rounding and taxes. */
    private void checkAmount(
            RiskContext context, String type, DocumentExtraction.Fields fields, List<String> findings) {
        BigDecimal claimed = context.claim() == null ? null : context.claim().claimedAmount();
        if (fields.amount() == null || claimed == null || claimed.signum() == 0) {
            return;
        }
        BigDecimal tolerance = claimed.multiply(AMOUNT_TOLERANCE_RATIO).abs();
        BigDecimal difference = fields.amount().subtract(claimed).abs();
        if (difference.compareTo(tolerance) > 0) {
            findings.add(String.format(
                    "El importe del documento '%s' ($%s) difiere del monto reclamado ($%s)",
                    type, fields.amount(), claimed));
        }
    }
}
