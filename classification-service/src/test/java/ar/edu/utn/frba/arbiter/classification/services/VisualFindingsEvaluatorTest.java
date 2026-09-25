package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VisualFindingsEvaluatorTest {

    private final VisualFindingsEvaluator evaluator = new VisualFindingsEvaluator();

    @Test
    void signsOnSeveralDocuments_oneWarningAndOneFailingRow() {
        Map<String, DocumentExtraction> documents = new LinkedHashMap<>();
        documents.put("police_report", withSigns("El sello está pixelado"));
        documents.put("purchase_proof", withSigns("Importe pegado", "Fecha con otra tipografía"));
        documents.put("imei_deregistration", withSigns());

        VisualFindingsEvaluator.Result result = evaluator.evaluate(documents);

        assertThat(result.reasons()).singleElement().satisfies(reason -> assertThat(reason)
                .isEqualTo("Señales de adulteración en la documentación: "
                        + "«El sello está pixelado»; «Importe pegado»; «Fecha con otra tipografía»."));
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.ruleType()).isEqualTo("VISUAL_TAMPERING");
            assertThat(finding.passed()).isFalse();
            assertThat(finding.ruleId()).isNull();
            // Only the documents that carry signs: a clean one is not evidence of anything.
            assertThat(finding.evaluatedValue()).isEqualTo("documents=police_report,purchase_proof signs=3");
        });
    }

    /** Absence of signs proves nothing, so it writes nothing — never a PASS. */
    @Test
    void noSigns_noWarningAndNoRow() {
        VisualFindingsEvaluator.Result result = evaluator.evaluate(Map.of("purchase_proof", withSigns()));

        assertThat(result.reasons()).isEmpty();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void noDocumentsRead_nothingToSay() {
        assertThat(evaluator.evaluate(Map.of()).findings()).isEmpty();
    }

    private static DocumentExtraction withSigns(String... signs) {
        return new DocumentExtraction("texto", List.of(signs), DocumentExtraction.Fields.none());
    }
}
