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

/** Above all, a missing field must never be read as a contradiction. */
class DocumentInconsistencyEvaluatorTest {

    private static final String INSURED_IMEI = "351000000000042";
    /** RiskFixtures.EVENT_DATE is 13/06/2026. */
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

    /** A laptop policy: an insured item to cross against, and no IMEI. */
    private InsuredPolicy laptopPolicy() {
        return InsuredPolicy.builder()
                .policyNumber("POL-TEC-2026-010")
                .insuredId("40.123.456")
                .branch("Tecnología Portátil")
                .insuredItem("Notebook Lenovo IdeaPad 3")
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

    private DocumentExtraction.Fields fields(LocalDate documentDate, BigDecimal amount, String imei) {
        return new DocumentExtraction.Fields(
                documentDate, amount, null, null, null, imei, null, null, List.of());
    }

    private DocumentExtraction.Fields itemFields(String brand, String model) {
        return new DocumentExtraction.Fields(null, null, null, brand, model, null, null, null, List.of());
    }

    /** No documents analyzed: not evaluable, rather than a 0.0 that would dilute the score. */
    @Test
    void withoutAnalyzedDocuments_isNotEvaluable() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of()));

        assertThat(c.factorId()).isEqualTo(RiskFactorIds.DOCUMENT_INCONSISTENCY);
        assertThat(c.rationale()).contains("no evaluable");
    }

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

    /** The most important property: null means "the document doesn't say", never "doesn't match". */
    @Test
    void aMissingFieldIsNeverAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "police_report", withFields(DocumentExtraction.Fields.none()))));

        assertThat(c.score()).isEqualTo(0.0);
        assertThat(c.rationale()).contains("coinciden");
    }

    /** Without an IMEI on the policy (laptops) the check doesn't take part. */
    @Test
    void withoutAnImeiOnThePolicy_theCheckDoesNotParticipate() {
        InsuredPolicy noImei = RiskFixtures.policy(true, new BigDecimal("400000"));

        Contribution c = evaluator.evaluate(context(noImei, Map.of(
                "invoice", withFields(fields(null, null, "359999999999999")))));

        assertThat(c.score()).isEqualTo(0.0);
    }

    @Test
    void aDocumentDatedBeforeTheEvent_isAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "police_report", withFields(fields(EVENT_DAY.minusMonths(3), null, null)))));

        assertThat(c.score()).isGreaterThan(0.0);
        assertThat(c.rationale()).contains("anterior al hecho");
    }

    /** A purchase invoice is legitimately earlier: the tolerance lets it through. */
    @Test
    void aDocumentDatedAFewDaysBefore_isTolerated() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "invoice", withFields(fields(EVENT_DAY.minusDays(3), null, null)))));

        assertThat(c.score()).isEqualTo(0.0);
    }

    /** The fixture's claimed amount is 100,000. */
    @Test
    void anAmountFarFromTheClaimedOne_isAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "invoice", withFields(fields(null, new BigDecimal("450000"), null)))));

        assertThat(c.score()).isGreaterThan(0.0);
        assertThat(c.rationale()).contains("difiere del monto reclamado");
    }

    @Test
    void anAmountWithinToleranceIsNotAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "invoice", withFields(fields(null, new BigDecimal("105000"), null)))));

        assertThat(c.score()).isEqualTo(0.0);
    }

    /** The declared police report date against the certificate's. */
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

    /** Same day: no contradiction (the time isn't compared). */
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

    /** Two contradictions on the same document saturate the factor. */
    @Test
    void twoInconsistenciesSaturateTheFactor() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "invoice", withFields(fields(EVENT_DAY.minusMonths(3), new BigDecimal("450000"), "359999999999999")))));

        assertThat(c.score()).isEqualTo(1.0);
    }

    // ─── Make and model against the insured item ─────────────────────────────────

    @Test
    void aRepairInvoiceForAnotherMake_isFlagged() {
        Contribution c = evaluator.evaluate(context(laptopPolicy(),
                Map.of("repair_quote", withFields(itemFields("Asus", "VivoBook 15")))));

        assertThat(c.rationale()).contains("La marca del documento").contains("Asus");
    }

    /** Present in the insured item with different case: no finding. */
    @Test
    void theInsuredMake_writtenInAnotherCase_isNotAnInconsistency() {
        Contribution c = evaluator.evaluate(context(laptopPolicy(),
                Map.of("repair_quote", withFields(itemFields("LENOVO", "IdeaPad 3")))));

        assertThat(c.score()).isZero();
    }

    /** The model is only checked once the make matched, or it would repeat the make finding. */
    @Test
    void aWrongMake_isReportedOnce_notAlsoAsAWrongModel() {
        Contribution c = evaluator.evaluate(context(laptopPolicy(),
                Map.of("repair_quote", withFields(itemFields("Asus", "VivoBook 15")))));

        assertThat(c.rationale()).doesNotContain("El modelo del documento");
    }

    @Test
    void aDocumentWithoutAMake_staysSilent() {
        Contribution c = evaluator.evaluate(context(laptopPolicy(),
                Map.of("police_report", withFields(itemFields(null, null)))));

        assertThat(c.score()).isZero();
    }

    /** Without an {@code insuredItem} there's nothing to cross against, so no finding. */
    @Test
    void withoutAnInsuredItemOnThePolicy_theMakeIsNotChecked() {
        Contribution c = evaluator.evaluate(context(policyWithImei(),
                Map.of("repair_quote", withFields(itemFields("Asus", "VivoBook 15")))));

        assertThat(c.score()).isZero();
    }
}
