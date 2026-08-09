package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lo que el referente configura tiene que terminar en el prompt: es la razón de ser del backoffice
 * de reglas. Sin esto, {@code RulesRestAdapter} puede traer los textos de la BD y perderlos acá sin
 * que nada falle — el modelo simplemente clasifica peor y nadie se entera.
 */
class PromptBuilderTest {

    private PromptBuilder promptBuilder;

    @BeforeEach
    void setUp() throws IOException {
        promptBuilder = new PromptBuilder(new ClassPathResource("prompts/classification-v1.md"));
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
}
