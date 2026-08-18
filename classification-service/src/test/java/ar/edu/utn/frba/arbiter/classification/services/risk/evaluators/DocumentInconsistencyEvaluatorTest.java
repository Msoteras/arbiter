package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator.Contribution;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D4b · the factor stopped being a stub. What's tested here is that it compares **fields**, and
 * above all that a missing field isn't read as a contradiction: a police certificate carries no
 * IMEI, and that can't turn into a fraud signal.
 */
class DocumentInconsistencyEvaluatorTest {

    private static final String INSURED_IMEI = "351000000000042";
    /** RiskFixtures.EVENT_DATE es 13/06/2026. */
    private static final LocalDate EVENT_DAY = LocalDate.of(2026, 6, 13);

    private final DocumentInconsistencyEvaluator evaluator = new DocumentInconsistencyEvaluator();

    private InsuredPolicy policyWithImei() {
        return InsuredPolicy.builder()
                .policyNumber("POL-CEL-2024-001")
                .insuredId("40.123.456")
                .branch("Celulares")
                .imei(INSURED_IMEI)
                .effectiveFrom(RiskFixtures.POLICY_START.atStartOfDay())
                .effectiveTo(RiskFixtures.POLICY_START.plusYears(1).atStartOfDay())
                .upToDate(true)
                .insuredAmount(new BigDecimal("400000"))
                .coverages(List.of())
                .applicableClauses(List.of())
                .build();
    }

    private RiskContext context(InsuredPolicy policy, Map<String, DocumentExtraction> documents) {
        return new RiskContext(
                RiskFixtures.claim(new BigDecimal("100000")),
                policy,
                RiskFixtures.history(0),
                RiskFixtures.rules(null),
                null,
                documents);
    }

    private DocumentExtraction withFields(DocumentExtraction.Fields fields) {
        return new DocumentExtraction("texto del documento", List.of(), fields);
    }

    /** Only the fields this test varies; the rest go empty. */
    private DocumentExtraction.Fields fields(LocalDate documentDate, BigDecimal amount, String imei) {
        return new DocumentExtraction.Fields(documentDate, amount, null, imei, null);
    }

    /** With no documents analyzed nothing is known: not evaluable, not a 0.0 that would cheapen the score. */
    @Test
    void withoutAnalyzedDocuments_isNotEvaluable() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of()));

        assertThat(c.factorId()).isEqualTo(RiskFactorIds.DOCUMENT_INCONSISTENCY);
        assertThat(c.rationale()).contains("no evaluable");
    }

    /** The cross-check that motivated the factor. */
    @Test
    void anImeiThatDoesNotMatchTheInsuredItem_isAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "invoice", withFields(fields(null, null, "359999999999999")))));

        assertThat(c.score()).isGreaterThan(0.0);
        assertThat(c.rationale()).contains("IMEI").contains("no coincide");
    }

    @Test
    void theSameImeiIsNotAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "invoice", withFields(fields(null, null, INSURED_IMEI)))));

        assertThat(c.score()).isEqualTo(0.0);
    }

    /**
     * The evaluator's most important property: null is "the document doesn't say", never "doesn't
     * match". A police certificate with no IMEI can't add risk.
     */
    @Test
    void aMissingFieldIsNeverAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "police_report", withFields(DocumentExtraction.Fields.none()))));

        assertThat(c.score()).isEqualTo(0.0);
        assertThat(c.rationale()).contains("coinciden");
    }

    /** Sin IMEI en la póliza (Tecnología Portátil) el chequeo no participa. */
    @Test
    void withoutAnImeiOnThePolicy_theCheckDoesNotParticipate() {
        InsuredPolicy noImei = RiskFixtures.policy(true, new BigDecimal("400000"));

        Contribution c = evaluator.evaluate(context(noImei, Map.of(
                "invoice", withFields(fields(null, null, "359999999999999")))));

        assertThat(c.score()).isEqualTo(0.0);
    }

    /** A certificate dated months before the event can't be about that event. */
    @Test
    void aDocumentDatedBeforeTheEvent_isAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "police_report", withFields(fields(EVENT_DAY.minusMonths(3), null, null)))));

        assertThat(c.score()).isGreaterThan(0.0);
        assertThat(c.rationale()).contains("anterior al hecho");
    }

    /** The device's purchase invoice is legitimately earlier: the tolerance lets it through. */
    @Test
    void aDocumentDatedAFewDaysBefore_isTolerated() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "invoice", withFields(fields(EVENT_DAY.minusDays(3), null, null)))));

        assertThat(c.score()).isEqualTo(0.0);
    }

    /** El monto reclamado en el fixture es 100.000. */
    @Test
    void anAmountFarFromTheClaimedOne_isAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "invoice", withFields(fields(null, new BigDecimal("450000"), null)))));

        assertThat(c.score()).isGreaterThan(0.0);
        assertThat(c.rationale()).contains("difiere del monto reclamado");
    }

    /** Small gaps (VAT, rounding, shipping) aren't contradictions. */
    @Test
    void anAmountWithinToleranceIsNotAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "invoice", withFields(fields(null, new BigDecimal("105000"), null)))));

        assertThat(c.score()).isEqualTo(0.0);
    }

    /**
     * D12 · what the insured declared against what the certificate says. It's the signal that
     * justifies storing both dates separately.
     */
    @Test
    void aPoliceReportDatedDifferentlyFromWhatWasDeclared_isAnInconsistency() {
        RiskContext context = new RiskContext(
                RiskFixtures.claimWithPoliceReport(EVENT_DAY.plusDays(1).atTime(10, 0)),
                policyWithImei(),
                RiskFixtures.history(0),
                RiskFixtures.rules(null),
                null,
                Map.of("police_report", withFields(fields(EVENT_DAY.plusDays(9), null, null))));

        Contribution c = evaluator.evaluate(context);

        assertThat(c.score()).isGreaterThan(0.0);
        assertThat(c.rationale()).contains("declaró haber denunciado");
    }

    /** Same day declared and on the paper: no contradiction (the time isn't compared). */
    @Test
    void aPoliceReportMatchingTheDeclaredDay_isNotAnInconsistency() {
        RiskContext context = new RiskContext(
                RiskFixtures.claimWithPoliceReport(EVENT_DAY.plusDays(1).atTime(10, 0)),
                policyWithImei(),
                RiskFixtures.history(0),
                RiskFixtures.rules(null),
                null,
                Map.of("police_report", withFields(fields(EVENT_DAY.plusDays(1), null, null))));

        assertThat(evaluator.evaluate(context).score()).isEqualTo(0.0);
    }

    /** Dos contradicciones sobre el mismo documento saturan el factor. */
    @Test
    void twoInconsistenciesSaturateTheFactor() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "invoice", withFields(fields(EVENT_DAY.minusMonths(3), new BigDecimal("450000"), "359999999999999")))));

        assertThat(c.score()).isEqualTo(1.0);
    }
}
