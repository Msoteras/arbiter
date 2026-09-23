package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.config.LlmProperties;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
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

/** What the referente configures must reach the prompt; losing it here would fail silently. */
class PromptBuilderTest {

    private PromptBuilder promptBuilder;

    /** Built from the configured version, so the test also checks that template exists on the classpath. */
    @BeforeEach
    void setUp() throws IOException {
        promptBuilder = new PromptBuilder(
                new LlmProperties("ollama", "classification-v5"),
                new DefaultResourceLoader());
    }

    @Test
    void buildFullPrompt_rendersTheClaimCauseCatalogWithEachCausesCoverage() {
        String prompt = promptBuilder.buildFullPrompt(requestWithCatalog(List.of(
                new ClassificationRequest.ClaimCauseOption(2L, "Robo en vía pública", true),
                new ClassificationRequest.ClaimCauseOption(3L, "Hurto", false))));

        assertThat(prompt)
                .contains("- Robo en vía pública — CUBIERTO")
                .contains("- Hurto — NO CUBIERTO POR ESTA PÓLIZA")
                .doesNotContain("{{claimCauseCatalog}}");
    }

    @Test
    void buildFullPrompt_withNoCatalogTellsTheModelToStandDown() {
        String prompt = promptBuilder.buildFullPrompt(requestWithCatalog(List.of()));

        assertThat(prompt).contains("Catálogo no disponible").contains("AMBIGUOUS");
    }

    private ClassificationRequest requestWithCatalog(
            List<ClassificationRequest.ClaimCauseOption> catalog) {
        return ClassificationRequest.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Robo en vía pública")
                .insuredItem("Samsung A56")
                .description("Me sacaron el celular en la calle")
                .claimCauseCatalog(catalog)
                .build();
    }

    private InsuredPolicy policy() {
        return InsuredPolicy.builder()
                .policyNumber("POL-CEL-2026-001")
                .branch("Celulares")
                .upToDate(true)
                .effectiveFrom(LocalDate.of(2026, 1, 1).atStartOfDay())
                .effectiveTo(LocalDate.of(2026, 12, 31).atStartOfDay())
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

    /** The rendered text reaches the final prompt through {@code insurerRules}. */
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

    /** The hard rules' verdict is injected into the prompt. */
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

    /** The amount is optional: without it the prompt says "No declarado", not null. */
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

    /** Visual findings must reach the classifier separately from the transcription. */
    @Test
    void renderHistory_translatesArbiterStatusesAndLeavesTheCompanysOwnUntouched() {
        InsuredHistory history = InsuredHistory.builder()
                .insuredId("33.845.219")
                .previousClaimsCount(2)
                .totalAmountClaimed(new BigDecimal("180000"))
                .customerSince(LocalDate.of(2024, 3, 1))
                .claims(List.of(
                        InsuredHistory.ClaimRecord.builder()
                                .claimId("7").date(LocalDate.of(2025, 11, 4))
                                .branch("Celulares").claimCause("Hurto")
                                .status("LIQUIDADO").amountSettled(new BigDecimal("180000"))
                                .build(),
                        InsuredHistory.ClaimRecord.builder()
                                .claimId("arbiter-41").date(LocalDate.of(2026, 9, 3))
                                .branch("Celulares").claimCause("Hurto")
                                .status("PENDING_ANALYST_REVIEW")
                                .build()))
                .build();

        String rendered = promptBuilder.renderHistory(history);

        // Arbiter's enum literal is translated; the insurer's own vocabulary passes through.
        assertThat(rendered).contains("Estado: en revisión del analista");
        assertThat(rendered).contains("Estado: LIQUIDADO");
        assertThat(rendered).doesNotContain("PENDING_ANALYST_REVIEW");
    }

    @Test
    void renderHistory_neverWordsAnApprovedCaseAsSettled() {
        InsuredHistory history = InsuredHistory.builder()
                .insuredId("33.845.219")
                .previousClaimsCount(1)
                .totalAmountClaimed(BigDecimal.ZERO)
                .customerSince(LocalDate.of(2026, 1, 1))
                .claims(List.of(InsuredHistory.ClaimRecord.builder()
                        .claimId("arbiter-12").date(LocalDate.of(2026, 8, 1))
                        .branch("Celulares").claimCause("Robo en vía pública")
                        .status("APPROVED")
                        .build()))
                .build();

        String rendered = promptBuilder.renderHistory(history);

        // Approval isn't payment: "liquidado" would invent a payment that may never have happened.
        assertThat(rendered).contains("aprobado por el analista (pendiente de liquidación)");
        assertThat(rendered).doesNotContain("LIQUIDADO");
    }

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

    /** With no findings (the normal case) no heading suggesting suspicion is added. */
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
