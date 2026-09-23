package ar.edu.utn.frba.arbiter.classification.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The sum insured and deductible the engine reads are the claim's coverage's, not the first one listed. */
class InsuredPolicyCoverageTest {

    @Test
    void narrowsTheAmountsToTheCoverageThatAnswers() {
        InsuredPolicy policy = policyWithBothCoverages();

        InsuredPolicy hurto = policy.forCoverage("Hurto");

        assertThat(hurto.insuredAmount()).isEqualByComparingTo("650000");
        assertThat(hurto.deductible()).isEqualByComparingTo("65000");
        // Validity and arrears belong to the policy, not to the coverage.
        assertThat(hurto.policyNumber()).isEqualTo(policy.policyNumber());
        assertThat(hurto.upToDate()).isEqualTo(policy.upToDate());
        assertThat(hurto.coverages()).isEqualTo(policy.coverages());
    }

    /** A theft claim on a robbery+theft policy must be measured against the theft coverage's sum. */
    @Test
    void withoutNarrowing_theAmountIsTheFirstCoverages() {
        assertThat(policyWithBothCoverages().insuredAmount()).isEqualByComparingTo("1300000");
    }

    @Test
    void matchesTheNameCaseInsensitively() {
        assertThat(policyWithBothCoverages().forCoverage("hurto").insuredAmount())
                .isEqualByComparingTo("650000");
    }

    /** An unknown or absent name leaves the policy untouched: a null sum insured would silently disable rules. */
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
