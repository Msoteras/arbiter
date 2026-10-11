package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    private static final Pattern NOT_A_WORD = Pattern.compile("[^\\p{L}\\d]+");

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
            checkItemAttributes(context, type, extraction.fields(), findings);
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
     * Each attribute the policy states about the insured item against the same one read from the
     * document. Driven by the policy's rows, so a line of insurance with other attributes needs no
     * code here. At most one finding per document: a wrong make comes with a wrong model, and
     * reporting both would count one contradiction twice.
     */
    private void checkItemAttributes(
            RiskContext context, String type, DocumentExtraction.Fields fields, List<String> findings) {
        Map<String, String> insured = context.policy() == null ? null : context.policy().itemAttributes();
        if (insured == null) {
            return;
        }
        Map<String, String> onDocument = documentAttributes(fields);
        for (Map.Entry<String, String> attribute : insured.entrySet()) {
            String read = onDocument.get(attribute.getKey().toLowerCase());
            if (read == null || attribute.getValue() == null || sameValue(read, attribute.getValue())) {
                continue;
            }
            findings.add(String.format(
                    "%s del documento '%s' (%s) no coincide con la póliza (%s)",
                    capitalized(attribute.getKey()), type, read, attribute.getValue()));
            return;
        }
    }

    /**
     * What the extraction reads about the item, under the names the insurer database uses. The
     * extraction still has fixed fields; the IMEI stays out because it is compared exactly, above.
     */
    private static Map<String, String> documentAttributes(DocumentExtraction.Fields fields) {
        Map<String, String> attributes = new HashMap<>();
        if (fields.brand() != null) {
            attributes.put("marca", fields.brand());
        }
        if (fields.model() != null) {
            attributes.put("modelo", fields.model());
        }
        return attributes;
    }

    /**
     * Compared by words, never as literal text: a document spells a value out further than the
     * policy does ("Galaxy A56 5G — 256 GB" for an insured "Galaxy A56"), and that is detail, not a
     * contradiction. Words carrying a digit are what tells two items apart ("A56", a plate), so when
     * both sides have them one set has to contain the other; otherwise sharing a word is enough.
     */
    private static boolean sameValue(String onDocument, String insured) {
        Set<String> documentWords = words(onDocument);
        Set<String> insuredWords = words(insured);
        Set<String> documentCodes = codes(documentWords);
        Set<String> insuredCodes = codes(insuredWords);
        if (!documentCodes.isEmpty() && !insuredCodes.isEmpty()) {
            return documentCodes.containsAll(insuredCodes) || insuredCodes.containsAll(documentCodes);
        }
        return documentWords.isEmpty() || !Collections.disjoint(documentWords, insuredWords);
    }

    private static Set<String> words(String text) {
        Set<String> words = new HashSet<>();
        for (String word : NOT_A_WORD.split(text.toLowerCase())) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    private static Set<String> codes(Set<String> words) {
        Set<String> codes = new HashSet<>();
        for (String word : words) {
            if (word.chars().anyMatch(Character::isDigit)) {
                codes.add(word);
            }
        }
        return codes;
    }

    private static String capitalized(String name) {
        return name.substring(0, 1).toUpperCase() + name.substring(1);
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
