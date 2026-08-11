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
 * D4b · el factor dejó de ser un stub. Lo que se prueba acá es que compare **campos**, y sobre todo
 * que un campo ausente no se lea como una contradicción: una constancia policial no trae IMEI, y eso
 * no puede convertirse en una señal de fraude.
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
                .effectiveFrom(RiskFixtures.POLICY_START)
                .effectiveTo(RiskFixtures.POLICY_START.plusYears(1))
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

    /** Solo los campos que este test hace variar; el resto va vacío. */
    private DocumentExtraction.Fields fields(LocalDate documentDate, BigDecimal amount, String imei) {
        return new DocumentExtraction.Fields(documentDate, amount, null, imei, null);
    }

    /** Sin documentos analizados no se sabe nada: no evaluable, no un 0.0 que abarataría el score. */
    @Test
    void withoutAnalyzedDocuments_isNotEvaluable() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of()));

        assertThat(c.factorId()).isEqualTo(RiskFactorIds.DOCUMENT_INCONSISTENCY);
        assertThat(c.rationale()).contains("no evaluable");
    }

    /** El cruce que motivó el factor. */
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
     * Lo más importante del evaluador: null es "el documento no lo dice", nunca "no coincide". Una
     * constancia policial sin IMEI no puede sumar riesgo.
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

    /** Una constancia fechada meses antes del hecho no puede ser de ese hecho. */
    @Test
    void aDocumentDatedBeforeTheEvent_isAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "police_report", withFields(fields(EVENT_DAY.minusMonths(3), null, null)))));

        assertThat(c.score()).isGreaterThan(0.0);
        assertThat(c.rationale()).contains("anterior al hecho");
    }

    /** La factura de compra del equipo es legítimamente previa: la tolerancia la deja pasar. */
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

    /** Diferencias chicas (IVA, redondeo, envío) no son contradicciones. */
    @Test
    void anAmountWithinToleranceIsNotAnInconsistency() {
        Contribution c = evaluator.evaluate(context(policyWithImei(), Map.of(
                "invoice", withFields(fields(null, new BigDecimal("105000"), null)))));

        assertThat(c.score()).isEqualTo(0.0);
    }

    /**
     * D12 · lo declarado por el asegurado contra lo que dice la constancia. Es la señal que
     * justifica guardar las dos fechas por separado.
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

    /** Mismo día declarado y en el papel: no hay contradicción (no se compara la hora). */
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
