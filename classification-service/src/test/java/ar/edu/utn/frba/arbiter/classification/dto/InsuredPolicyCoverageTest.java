package ar.edu.utn.frba.arbiter.classification.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La suma asegurada y la franquicia que lee todo el motor (Fast Track, {@code amount_ratio}, el
 * agotamiento por monto, el prompt, el {@code policy_snapshot} auditado) son las de la cobertura
 * que responde por el siniestro, no las de la primera que devolvió la compañía.
 */
class InsuredPolicyCoverageTest {

    @Test
    void narrowsTheAmountsToTheCoverageThatAnswers() {
        InsuredPolicy policy = policyWithBothCoverages();

        InsuredPolicy hurto = policy.forCoverage("Hurto");

        assertThat(hurto.insuredAmount()).isEqualByComparingTo("650000");
        assertThat(hurto.deductible()).isEqualByComparingTo("65000");
        // El resto de la póliza no se toca: la vigencia y la mora son del contrato, no del riesgo.
        assertThat(hurto.policyNumber()).isEqualTo(policy.policyNumber());
        assertThat(hurto.upToDate()).isEqualTo(policy.upToDate());
        assertThat(hurto.coverages()).isEqualTo(policy.coverages());
    }

    /**
     * El bug concreto: sin estrechar, un hurto sobre una póliza que cubre robo y hurto se medía
     * contra la suma asegurada del robo — el doble, en la póliza del seed.
     */
    @Test
    void withoutNarrowing_theAmountIsTheFirstCoverages() {
        assertThat(policyWithBothCoverages().insuredAmount()).isEqualByComparingTo("1300000");
    }

    @Test
    void matchesTheNameCaseInsensitively() {
        assertThat(policyWithBothCoverages().forCoverage("hurto").insuredAmount())
                .isEqualByComparingTo("650000");
    }

    /**
     * Un nombre que no está (o ausente) deja la póliza como venía. Es lo que hacía el código
     * anterior, y es mejor que una suma asegurada nula, que apaga en silencio toda regla que
     * divida por ella.
     */
    @Test
    void anUnknownOrMissingCoverageLeavesThePolicyUntouched() {
        InsuredPolicy policy = policyWithBothCoverages();

        assertThat(policy.forCoverage("Daño accidental")).isSameAs(policy);
        assertThat(policy.forCoverage(null)).isSameAs(policy);
    }

    private InsuredPolicy policyWithBothCoverages() {
        return InsuredPolicy.builder()
                .policyNumber("POL-CEL-2026-042")
                .insuredName("Martina Soteras")
                .branch("Celulares")
                .upToDate(true)
                .insuredAmount(new BigDecimal("1300000"))
                .deductible(new BigDecimal("130000"))
                .coverages(List.of(
                        InsuredPolicy.PolicyCoverage.builder()
                                .code("COB-1").description("Robo de celular")
                                .insuredAmount(new BigDecimal("1300000"))
                                .deductible(new BigDecimal("130000"))
                                .build(),
                        InsuredPolicy.PolicyCoverage.builder()
                                .code("COB-2").description("Hurto")
                                .insuredAmount(new BigDecimal("650000"))
                                .deductible(new BigDecimal("65000"))
                                .build()))
                .build();
    }
}
