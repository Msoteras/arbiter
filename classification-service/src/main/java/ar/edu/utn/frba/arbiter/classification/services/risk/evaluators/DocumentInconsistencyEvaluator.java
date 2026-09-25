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
 * Contradictions between the documents' typed fields and the claim: the model reads, the code
 * compares. A missing field is never an inconsistency, and with no documents examined the factor is
 * not evaluable rather than 0.0.
 */
@Component
public class DocumentInconsistencyEvaluator implements RiskFactorEvaluator {

    /** Absorbs rounding, VAT and shipping. */
    private static final BigDecimal AMOUNT_TOLERANCE_RATIO = new BigDecimal("0.10");

    private static final int DOCUMENT_DATE_TOLERANCE_DAYS = 7;

    private static final double SCORE_PER_FINDING = 0.5;

    private static final String POLICE_REPORT_TYPE = "police_report";

    /** Predates the event by nature: the item was bought before it was stolen or broken. */
    private static final String PURCHASE_PROOF_TYPE = "purchase_proof";

    /** On a damage claim, the document that sets what is being claimed. */
    private static final String REPAIR_QUOTE_TYPE = "repair_quote";

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
            checkBrandAndModel(context, type, extraction.fields(), findings);
            checkDocumentDate(context, type, extraction.fields(), findings);
        });
        checkAmount(context, documents, findings);
        checkDeclaredPoliceReportDate(context, documents, findings);

        if (findings.isEmpty()) {
            return new Contribution(factorId(), 0.0,
                    "Los datos de los documentos coinciden con los del siniestro");
        }
        double score = Math.min(1.0, findings.size() * SCORE_PER_FINDING);
        return new Contribution(factorId(), score, String.join(" · ", findings));
    }

    /** Only runs when the policy has an IMEI (cellphone branch). */
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
     * The insured item is free text, so this only flags a make (or, once the make matched, a model)
     * that is <b>absent</b> from it; a present one proves nothing on its own.
     */
    private void checkBrandAndModel(
            RiskContext context, String type, DocumentExtraction.Fields fields, List<String> findings) {
        String insuredItem = context.policy() == null ? null : context.policy().insuredItem();
        if (insuredItem == null || insuredItem.isBlank() || fields.brand() == null) {
            return;
        }
        String haystack = insuredItem.toLowerCase();
        if (!haystack.contains(fields.brand().toLowerCase().trim())) {
            findings.add(String.format(
                    "La marca del documento '%s' (%s) no aparece en el bien asegurado (%s)",
                    type, fields.brand(), insuredItem));
            return;
        }
        if (fields.model() != null && !haystack.contains(fields.model().toLowerCase().trim())) {
            findings.add(String.format(
                    "El modelo del documento '%s' (%s) no aparece en el bien asegurado (%s)",
                    type, fields.model(), insuredItem));
        }
    }

    /**
     * Documents about the event are issued after it, with a week of slack. The purchase proof is left
     * out: it always predates the event, so checking it flagged every claim that carried one.
     */
    private void checkDocumentDate(
            RiskContext context, String type, DocumentExtraction.Fields fields, List<String> findings) {
        if (PURCHASE_PROOF_TYPE.equals(type)
                || fields.documentDate() == null || context.claim() == null || context.claim().eventDate() == null) {
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

    /** Declared police report date vs. the certificate's, by day: certificates rarely carry the time. */
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

    /**
     * Only against the document that sets what is claimed: the repair quote on a damage claim, the
     * purchase proof otherwise. Comparing every document flagged every damage claim, whose invoice
     * (what the item is worth) never matches the quote (what the repair costs).
     */
    private void checkAmount(
            RiskContext context, Map<String, DocumentExtraction> documents, List<String> findings) {
        String type = documents.containsKey(REPAIR_QUOTE_TYPE) ? REPAIR_QUOTE_TYPE : PURCHASE_PROOF_TYPE;
        DocumentExtraction document = documents.get(type);
        BigDecimal claimed = context.claim() == null ? null : context.claim().claimedAmount();
        if (document == null || claimed == null || claimed.signum() == 0) {
            return;
        }
        DocumentExtraction.Fields fields = document.fields();
        if (fields.amount() == null) {
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
