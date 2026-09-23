package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest.ClaimCauseOption;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cause a document narrates against the one the insured declared. What matters here is the
 * asymmetry the rule is built on: a document that says nothing is never a mismatch, and a mismatch
 * warns without deciding anything.
 */
class ClaimCauseConsistencyEvaluatorTest {

    private static final String ROBO = "Robo en vía pública";
    private static final String HURTO = "Hurto";

    /** The BBVA robo coverage: it excludes hurto (rule 21). */
    private static final List<ClaimCauseOption> CATALOG = List.of(
            new ClaimCauseOption(2L, ROBO, true),
            new ClaimCauseOption(3L, HURTO, false),
            new ClaimCauseOption(4L, "Caída", true));

    private final ClaimCauseConsistencyEvaluator evaluator = new ClaimCauseConsistencyEvaluator();

    private static ClaimReport declared(String cause) {
        return ClaimReport.builder().branch("Celulares").claimCause(cause).build();
    }

    private static DocumentExtraction narrating(String cause) {
        return new DocumentExtraction("...", List.of(),
                new DocumentExtraction.Fields(null, null, null, null, null, null, null, cause, List.of()));
    }

    @Test
    void anActaNarratingAnotherCause_warnsAndFails() {
        ClaimCauseConsistencyEvaluator.Result result = evaluator.evaluate(
                declared(ROBO), Map.of("police_report", narrating(HURTO)), CATALOG);

        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.ruleType()).isEqualTo("CLAIM_CAUSE_MATCH");
            assertThat(finding.passed()).isFalse();
            assertThat(finding.ruleId()).isNull();
            assertThat(finding.evaluatedValue())
                    .isEqualTo("declared=Robo en vía pública described=Hurto documents=police_report");
        });
        assertThat(result.reasons()).singleElement().asString()
                .contains("«Hurto»", "«Robo en vía pública»", "que esta cobertura no cubre");
    }

    /** Covered or not is the engine's word, and the warning only says it when it's true. */
    @Test
    void aCoveredNarratedCause_warnsWithoutClaimingItIsExcluded() {
        ClaimCauseConsistencyEvaluator.Result result = evaluator.evaluate(
                declared(ROBO), Map.of("police_report", narrating("Caída")), CATALOG);

        assertThat(result.reasons()).singleElement().asString()
                .contains("«Caída»")
                .doesNotContain("no cubre");
    }

    @Test
    void anActaNarratingTheDeclaredCause_passes() {
        ClaimCauseConsistencyEvaluator.Result result = evaluator.evaluate(
                declared(ROBO), Map.of("police_report", narrating("robo en vía pública")), CATALOG);

        assertThat(result.reasons()).isEmpty();
        assertThat(result.findings()).extracting(RuleFinding::passed).containsExactly(true);
    }

    /** An invoice narrates no event: with nothing to compare, no row — "nobody said" isn't a match. */
    @Test
    void noDocumentNarratingACause_leavesTheRuleUnevaluated() {
        ClaimCauseConsistencyEvaluator.Result result = evaluator.evaluate(
                declared(ROBO), Map.of("purchase_proof", narrating(null)), CATALOG);

        assertThat(result.findings()).isEmpty();
        assertThat(result.reasons()).isEmpty();
    }

    /** Only the documents that narrate take part: the silent invoice doesn't dilute the acta. */
    @Test
    void theSilentDocumentsDontTakePart() {
        Map<String, DocumentExtraction> documents = new LinkedHashMap<>();
        documents.put("purchase_proof", narrating(null));
        documents.put("police_report", narrating(HURTO));

        ClaimCauseConsistencyEvaluator.Result result = evaluator.evaluate(declared(ROBO), documents, CATALOG);

        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.evaluatedValue()).endsWith("documents=police_report"));
    }

    @Test
    void withoutDocuments_nothingIsEvaluated() {
        assertThat(evaluator.evaluate(declared(ROBO), Map.of(), CATALOG).findings()).isEmpty();
    }

    /** An empty catalog (couldn't be read) still compares names; it just can't say what's covered. */
    @Test
    void withoutCatalog_stillWarnsButSaysNothingAboutCoverage() {
        ClaimCauseConsistencyEvaluator.Result result = evaluator.evaluate(
                declared(ROBO), Map.of("police_report", narrating(HURTO)), List.of());

        assertThat(result.reasons()).singleElement().asString().doesNotContain("no cubre");
    }
}
