package ar.edu.utn.frba.arbiter.classification.services.risk.evaluators;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorEvaluator.Contribution;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
                .itemAttributes(Map.of("IMEI", INSURED_IMEI))
                .effectiveFrom(RiskFixtures.POLICY_START.atStartOfDay())
                .effectiveTo(RiskFixtures.POLICY_START.plusYears(1).atStartOfDay())
                .upToDate(true)
                .insuredAmount(new BigDecimal("400000"))
                .coverages(List.of())
                .applicableClauses(List.of())
                .build();
    }

    /** A laptop policy: item attributes to cross against, and no IMEI. */
    private InsuredPolicy laptopPolicy() {
        return policyInsuring("Lenovo", "IdeaPad 3");
    }

    private InsuredPolicy policyInsuring(String brand, String model) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("marca", brand);
        attributes.put("modelo", model);
        return policyWithAttributes(attributes);
    }

    private InsuredPolicy policyWithAttributes(Map<String, String> itemAttributes) {
        return InsuredPolicy.builder()
                .policyNumber("POL-TEC-2026-010")
                .insuredId("40.123.456")
                .branch("Tecnología Portátil")
                .insuredItem("the free text is never compared")
                .itemAttributes(itemAttributes)
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
                documentDate, amount, null, imei == null ? Map.of() : Map.of("IMEI", imei), null, null, List.of());
    }

    /** A null leaves the name out, which is how the extraction says "the document doesn't state it". */
    private DocumentExtraction.Fields itemFields(String brand, String model) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (brand != null) {
            attributes.put("marca", brand);
        }
        if (model != null) {
            attributes.put("modelo", model);
        }
        return new DocumentExtraction.Fields(null, null, null, attributes, null, null, List.of());
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

    /** The analyst reads this text: dates as dd/mm/yyyy, never ISO. */
    @Test
    void datesAreWrittenTheWayTheAnalystReadsThem() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "police_report", withFields(fields(LocalDate.of(2025, 12, 30), null, null)))));

        assertThat(c.rationale()).contains("30/12/2025").contains("13/06/2026").doesNotContain("2025-12-30");
    }

    /** What a provider answers a referral with is not the insured's documentation. */
    @ParameterizedTest
    @CsvSource({"expert_report", "repair_report"})
    void aProviderReport_isNotCrossedAgainstTheClaim(String type) {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                type, withFields(fields(EVENT_DAY.minusMonths(6), null, "359999999999999")))));

        assertThat(c.score()).isZero();
    }

    @Test
    void aDocumentDatedAFewDaysBefore_isTolerated() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "police_report", withFields(fields(EVENT_DAY.minusDays(3), null, null)))));

        assertThat(c.score()).isEqualTo(0.0);
    }

    /** The item was bought before it was stolen: an old purchase proof is the norm, not a finding. */
    @Test
    void aPurchaseProofMonthsBeforeTheEvent_isNotAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "purchase_proof", withFields(fields(EVENT_DAY.minusMonths(9), null, null)))));

        assertThat(c.score()).isEqualTo(0.0);
    }

    /** The fixture's claimed amount is 100,000. */
    @Test
    void anAmountFarFromTheClaimedOne_isAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "purchase_proof", withFields(fields(null, new BigDecimal("450000"), null)))));

        assertThat(c.score()).isGreaterThan(0.0);
        assertThat(c.rationale()).contains("difiere del monto reclamado");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "589999.0  | $ 589.999",
            "900000.00 | $ 900.000",
            "450000.50 | $ 450.000,5",
    })
    void amountsAreWrittenInArgentineFormat(BigDecimal onDocument, String shown) {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "purchase_proof", withFields(fields(null, onDocument, null)))));

        assertThat(c.rationale()).contains("(" + shown + ")").contains("($ 100.000)");
    }

    @Test
    void anAmountWithinToleranceIsNotAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "purchase_proof", withFields(fields(null, new BigDecimal("105000"), null)))));

        assertThat(c.score()).isEqualTo(0.0);
    }

    /**
     * A damage claim: what is claimed is the repair (the quote, 100,000), not what the item is worth
     * (the invoice, 450,000). Only the quote is compared.
     */
    @Test
    void onADamageClaim_onlyTheRepairQuoteIsComparedToTheClaimedAmount() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "purchase_proof", withFields(fields(null, new BigDecimal("450000"), null)),
                "repair_quote", withFields(fields(null, new BigDecimal("100000"), null)))));

        assertThat(c.score()).isEqualTo(0.0);
    }

    @Test
    void onADamageClaim_aQuoteFarFromTheClaimedAmount_isAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "purchase_proof", withFields(fields(null, new BigDecimal("100000"), null)),
                "repair_quote", withFields(fields(null, new BigDecimal("30000"), null)))));

        assertThat(c.rationale()).contains("\"Presupuesto de reparación\"").contains("difiere del monto reclamado");
    }

    /** Other documents' amounts (a police report's valuation, say) never take part. */
    @Test
    void anAmountOnADocumentThatDoesNotSetTheClaim_isIgnored() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "police_report", withFields(fields(null, new BigDecimal("450000"), null)))));

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
                "purchase_proof", withFields(fields(null, new BigDecimal("450000"), "359999999999999")))));

        assertThat(c.score()).isEqualTo(1.0);
    }

    /** Every kind of finding names the document in Spanish, never by its internal key. */
    @Test
    void findingsNameTheDocumentTheWayTheScreenDoes() {
        Contribution c = evaluator.evaluate(context(policyInsuring("Samsung", "Galaxy A56"), Map.of(
                "purchase_proof", withFields(new DocumentExtraction.Fields(
                        null, new BigDecimal("450000"), null,
                        Map.of("marca", "Samsung", "modelo", "Galaxy S24"), null, null, List.of())),
                "police_report", withFields(fields(EVENT_DAY.minusMonths(3), null, null)))));

        assertThat(c.rationale())
                .contains("Modelo del documento \"Comprobante de compra\"")
                .contains("El importe del documento \"Comprobante de compra\"")
                .contains("El documento \"Denuncia policial\"")
                .doesNotContain("purchase_proof")
                .doesNotContain("police_report");
    }

    /** The names come with the rules; a type the catalog doesn't name still shows, by its code. */
    @Test
    void aDocumentTheCatalogDoesNotName_isShownByItsCode() {
        Contribution c = evaluator.evaluate(context(laptopPolicy(),
                Map.of("warranty_card", withFields(itemFields("Asus", "VivoBook 15")))));

        assertThat(c.rationale()).contains("Marca del documento \"warranty_card\"");
    }

    // ─── Item attributes of the policy against the document ──────────────────────

    @Test
    void aRepairInvoiceForAnotherMake_isFlagged() {
        Contribution c = evaluator.evaluate(context(laptopPolicy(),
                Map.of("repair_quote", withFields(itemFields("Asus", "VivoBook 15")))));

        assertThat(c.rationale()).contains("Marca del documento").contains("Asus").contains("Lenovo");
    }

    @Test
    void theInsuredMake_writtenInAnotherCase_isNotAnInconsistency() {
        Contribution c = evaluator.evaluate(context(laptopPolicy(),
                Map.of("repair_quote", withFields(itemFields("LENOVO", "IdeaPad 3")))));

        assertThat(c.score()).isZero();
    }

    /** One finding per document, or one contradiction would be counted twice. */
    @Test
    void aWrongMake_isReportedOnce_notAlsoAsAWrongModel() {
        Contribution c = evaluator.evaluate(context(laptopPolicy(),
                Map.of("repair_quote", withFields(itemFields("Asus", "VivoBook 15")))));

        assertThat(c.rationale()).doesNotContain("Modelo del documento");
    }

    /** The document spells the model out further than the policy: detail, not a contradiction. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Samsung | Galaxy A56         | SAMSUNG | Galaxy A56 5G",
            "Samsung | Galaxy A56         | SAMSUNG | Galaxy A56 5G — 256 GB — Gris (Awesome Graphite)",
            "Samsung | Galaxy A56 256GB   | Samsung | Galaxy A56",
            "Apple   | iPhone 15 Pro      | APPLE   | iPhone 15 Pro Max",
            "Apple   | MacBook Air M3 15\" | Apple   | MacBook Air 15\" (M3, 2024)",
            "Apple   | MacBook Air M3 15\" | Apple   | MacBook Air",
            "Lenovo  | IdeaPad 3          | Lenovo  | IdeaPad 3 15ITL6",
    })
    void aValueWrittenWithMoreOrLessDetail_isNotAnInconsistency(
            String insuredBrand, String insuredModel, String brand, String model) {
        Contribution c = evaluator.evaluate(context(policyInsuring(insuredBrand, insuredModel),
                Map.of("purchase_proof", withFields(itemFields(brand, model)))));

        assertThat(c.score()).isZero();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Samsung | Galaxy A56 | SAMSUNG | Galaxy S24",
            "Samsung | Galaxy A56 | SAMSUNG | Galaxy A36 5G",
            "Lenovo  | IdeaPad 3  | Lenovo  | IdeaPad 5",
            "Lenovo  | IdeaPad 3  | Lenovo  | ThinkPad",
    })
    void anotherModelOfTheSameMake_isFlagged(
            String insuredBrand, String insuredModel, String brand, String model) {
        Contribution c = evaluator.evaluate(context(policyInsuring(insuredBrand, insuredModel),
                Map.of("purchase_proof", withFields(itemFields(brand, model)))));

        assertThat(c.rationale()).contains("Modelo del documento").contains(model);
    }

    @Test
    void aDocumentWithoutAMake_staysSilent() {
        Contribution c = evaluator.evaluate(context(laptopPolicy(),
                Map.of("police_report", withFields(itemFields(null, null)))));

        assertThat(c.score()).isZero();
    }

    /** A policy with no attribute rows gives nothing to cross against: the free text is not parsed. */
    @Test
    void withoutItemAttributesOnThePolicy_nothingIsCompared() {
        Contribution c = evaluator.evaluate(context(policyWithImei(),
                Map.of("repair_quote", withFields(itemFields("Asus", "VivoBook 15")))));

        assertThat(c.score()).isZero();
    }

    /** The policy and the document each write an identifier their own way: same one, no finding. */
    @Test
    void theSameIdentifier_spacedOrHyphenatedDifferently_isNotAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithAttributes(Map.of("patente", "AB 123 CD")),
                Map.of("repair_quote", withFields(new DocumentExtraction.Fields(
                        null, null, null, Map.of("Patente", "ab-123-cd"), null, null, List.of())))));

        assertThat(c.score()).isZero();
    }

    @Test
    void anotherIdentifierWithTheSameDigits_isFlagged() {
        Contribution c = evaluator.evaluate(context(policyWithAttributes(Map.of("patente", "AB123CD")),
                Map.of("repair_quote", withFields(new DocumentExtraction.Fields(
                        null, null, null, Map.of("patente", "AC123XY"), null, null, List.of())))));

        assertThat(c.rationale()).contains("Patente del documento").contains("AC123XY");
    }

    /** An attribute the document doesn't state is skipped, never read as a mismatch. */
    @Test
    void anAttributeTheDocumentDoesNotCarry_isSkipped() {
        Contribution c = evaluator.evaluate(context(policyWithAttributes(Map.of("patente", "AB123CD")),
                Map.of("repair_quote", withFields(itemFields("Asus", "VivoBook 15")))));

        assertThat(c.score()).isZero();
    }
}
