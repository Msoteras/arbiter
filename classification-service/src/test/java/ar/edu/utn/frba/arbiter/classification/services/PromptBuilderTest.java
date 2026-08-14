package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the referente configures has to end up in the prompt: it's the rules backoffice's whole
 * point. Without this, {@code RulesRestAdapter} can fetch the texts from the DB and lose them here
 * with nothing failing — the model simply classifies worse and nobody finds out.
 */
class PromptBuilderTest {

    private PromptBuilder promptBuilder;

    /**
     * Built the same way as in production — from the configured version — and not pointing at a
     * fixed file: this way the test also verifies the template of the audited version exists
     * de verdad en el classpath.
     */
    @BeforeEach
    void setUp() throws IOException {
        promptBuilder = new PromptBuilder(
                new OllamaProperties(null, "qwen3-vl", "classification-v4"),
                new DefaultResourceLoader());
    }

    private InsuredPolicy policy() {
        return InsuredPolicy.builder()
                .policyNumber("POL-CEL-2026-001")
                .branch("Celulares")
                .upToDate(true)
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .effectiveTo(LocalDate.of(2026, 12, 31))
                .insuredAmount(new BigDecimal("400000"))
                .applicableClauses(List.of())
                .build();
    }

    @Test
    void renderRulesAndPolicy_includesTheRulesTheReferenteWrote() {
        BusinessRules rules = BusinessRules.builder()
                .branchId("Celulares")
                .claimCauseId("Robo en vía pública")
                .rules(List.of("La denuncia policial debe presentarse dentro de las 48 hs"))
                .exclusions(List.of("Robo en el domicilio declarado en la póliza"))
                .fastTrackCriteria(List.of())
                .build();

        String rendered = promptBuilder.renderRulesAndPolicy(rules, policy());

        assertThat(rendered).contains("La denuncia policial debe presentarse dentro de las 48 hs");
        assertThat(rendered).contains("Robo en el domicilio declarado en la póliza");
    }

    @Test
    void renderRulesAndPolicy_omitsTheExclusionsHeadingWhenThereAreNone() {
        BusinessRules rules = BusinessRules.builder()
                .branchId("Celulares")
                .claimCauseId("Rotura accidental")
                .rules(List.of("Se requiere presupuesto de reparación"))
                .exclusions(List.of())
                .fastTrackCriteria(List.of())
                .build();

        String rendered = promptBuilder.renderRulesAndPolicy(rules, policy());

        assertThat(rendered).contains("Se requiere presupuesto de reparación");
        assertThat(rendered).doesNotContain("EXCLUSIONES DE COBERTURA");
    }

    /** El texto renderizado viaja al prompt final por {@code insurerRules} — el último eslabón. */
    @Test
    void buildFullPrompt_carriesTheRenderedRulesIntoTheTemplate() {
        BusinessRules rules = BusinessRules.builder()
                .branchId("Celulares")
                .claimCauseId("Robo en vía pública")
                .rules(List.of("Regla configurada por el referente"))
                .exclusions(List.of())
                .fastTrackCriteria(List.of())
                .build();

        ClassificationRequest request = ClassificationRequest.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Robo en vía pública")
                .insuredItem("Motorola Edge 50 Pro")
                .description("Me robaron el celular en la vía pública.")
                .insurerRules(promptBuilder.renderRulesAndPolicy(rules, policy()))
                .build();

        assertThat(promptBuilder.buildFullPrompt(request)).contains("Regla configurada por el referente");
    }

    /** D5: the event's date, location and claimed amount now travel to the prompt. */
    @Test
    void buildFullPrompt_includesEventDateLocationAndClaimedAmount() {
        ClassificationRequest request = ClassificationRequest.builder()
                .branch("Tecnología Portátil")
                .product("Seguro de Tecnología Portátil")
                .claimCause("Robo en vía pública")
                .insuredItem("MacBook Air M3")
                .description("Me robaron la notebook.")
                .eventDate(LocalDateTime.of(2026, 6, 30, 22, 15))
                .eventLocation("Av. Rivadavia 4820, CABA")
                .claimedAmount(new BigDecimal("1234567"))
                .insurerRules("sin reglas")
                .insuredHistory("sin historial")
                .build();

        String prompt = promptBuilder.buildFullPrompt(request);

        assertThat(prompt).contains("30/06/2026 22:15");
        assertThat(prompt).contains("Av. Rivadavia 4820, CABA");
        assertThat(prompt).contains("1.234.567");
    }

    /** D4a step 6: the engine's verdict (hard rules already evaluated) is injected into the prompt. */
    @Test
    void buildFullPrompt_injectsEngineEvaluation() {
        ClassificationRequest withFinding = ClassificationRequest.builder()
                .branch("Celulares").product("x").claimCause("Hurto").insuredItem("y")
                .description("z").insurerRules("sin reglas").insuredHistory("sin historial")
                .engineEvaluation(List.of("Denuncia fuera de plazo: 100 hs desde el hecho, supera el máximo de 72 hs"))
                .build();
        assertThat(promptBuilder.buildFullPrompt(withFinding))
                .contains("Denuncia fuera de plazo")
                .contains("ya fueron evaluadas por código");

        ClassificationRequest noFinding = ClassificationRequest.builder()
                .branch("Celulares").product("x").claimCause("Hurto").insuredItem("y")
                .description("z").insurerRules("sin reglas").insuredHistory("sin historial")
                .engineEvaluation(List.of())
                .build();
        assertThat(promptBuilder.buildFullPrompt(noFinding))
                .contains("no encontró incumplimientos de reglas duras");
    }

    /** The amount is optional (the wizard doesn't require it): with no value the prompt says "No declarado", not null. */
    @Test
    void buildFullPrompt_showsClaimedAmountAsNotDeclaredWhenNull() {
        ClassificationRequest request = ClassificationRequest.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Hurto")
                .insuredItem("Samsung Galaxy A56")
                .description("Me hurtaron el celular.")
                .eventDate(LocalDateTime.of(2026, 7, 4, 9, 30))
                .eventLocation("San Martín, Buenos Aires")
                .claimedAmount(null)
                .insurerRules("sin reglas")
                .insuredHistory("sin historial")
                .build();

        String prompt = promptBuilder.buildFullPrompt(request);

        assertThat(prompt).contains("No declarado");
    }

    /**
     * D5: the extraction pass's visual signal has to reach the classifier <b>separately</b>
     * de la transcripción. Si se mezclaran, el modelo leería "la firma está pixelada" como si lo
     * dijera el documento.
     */
    @Test
    void renderAttachment_keepsVisualFindingsApartFromTheTranscription() {
        String rendered = promptBuilder.renderAttachment(
                "police_report",
                new DocumentExtraction(
                        "Constancia de denuncia N° 4471/26, comisaría 15a, 30/06/2026.",
                        List.of("El número de acta usa una tipografía distinta al resto del formulario"),
                        DocumentExtraction.Fields.none()));

        assertThat(rendered)
                .contains("police_report: Constancia de denuncia N° 4471/26")
                .contains("no es contenido del documento")
                .contains("tipografía distinta al resto del formulario");
    }

    /** With no findings — the normal case — no heading suggesting suspicion is added. */
    @Test
    void renderAttachment_addsNothingWhenThereAreNoVisualFindings() {
        String rendered = promptBuilder.renderAttachment(
                "invoice", DocumentExtraction.of("Factura B 0001-00023456, $1.150.000, 12/05/2026."));

        assertThat(rendered).isEqualTo("invoice: Factura B 0001-00023456, $1.150.000, 12/05/2026.");
        assertThat(rendered).doesNotContain("Observado en la imagen");
    }

    /** The template block that tells the model how to weigh those signals. */
    @Test
    void buildFullPrompt_explainsHowToWeighVisualFindings() {
        ClassificationRequest request = ClassificationRequest.builder()
                .branch("Celulares").product("x").claimCause("Hurto").insuredItem("y")
                .description("z").insurerRules("sin reglas").insuredHistory("sin historial")
                .attachmentsOcr(List.of(promptBuilder.renderAttachment(
                        "police_report",
                        new DocumentExtraction("Constancia.", List.of("Sello deformado"), DocumentExtraction.Fields.none()))))
                .build();

        assertThat(promptBuilder.buildFullPrompt(request))
                .contains("No son concluyentes")
                .contains("Su ausencia no prueba nada")
                .contains("Sello deformado");
    }
}
