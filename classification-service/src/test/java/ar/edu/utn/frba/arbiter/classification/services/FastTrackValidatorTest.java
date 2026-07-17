package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FastTrackValidatorTest {

    private final FastTrackValidator validator = new FastTrackValidator();

    @Test
    void noThresholdsConfigured_neverFastTracks() {
        BusinessRules rules = baseRules().fastTrackThresholds(null).build();

        FastTrackValidator.Result result = validator.evaluate(claim(new BigDecimal("1000")), policy(), history(0), rules, Map.of());

        assertThat(result.fastTrack()).isFalse();
    }

    @Test
    void allThresholdsNull_neverFastTracks() {
        BusinessRules rules = baseRules()
                .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder().build())
                .build();

        FastTrackValidator.Result result = validator.evaluate(claim(new BigDecimal("1000")), policy(), history(0), rules, Map.of());

        assertThat(result.fastTrack()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("sin criterios activos"));
    }

    @Test
    void allThresholdsMet_fastTracks() {
        BusinessRules rules = baseRules()
                .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                        .maxClaimedAmountRatio(0.5)
                        .maxPriorClaims(0)
                        .requiresUpToDatePolicy(true)
                        .build())
                .build();

        FastTrackValidator.Result result = validator.evaluate(
                claim(new BigDecimal("200000")), policy(), history(0), rules, Map.of());

        assertThat(result.fastTrack()).isTrue();
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    void claimedAmountAboveRatio_doesNotFastTrack() {
        BusinessRules rules = baseRules()
                .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                        .maxClaimedAmountRatio(0.5)
                        .build())
                .build();

        FastTrackValidator.Result result = validator.evaluate(
                claim(new BigDecimal("900000")), policy(), history(0), rules, Map.of());

        assertThat(result.fastTrack()).isFalse();
    }

    @Test
    void claimedAmountMissing_doesNotFastTrackWhenRatioRequired() {
        BusinessRules rules = baseRules()
                .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                        .maxClaimedAmountRatio(0.5)
                        .build())
                .build();

        FastTrackValidator.Result result = validator.evaluate(claim(null), policy(), history(0), rules, Map.of());

        assertThat(result.fastTrack()).isFalse();
    }

    @Test
    void tooManyPriorClaims_doesNotFastTrack() {
        BusinessRules rules = baseRules()
                .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                        .maxPriorClaims(0)
                        .build())
                .build();

        FastTrackValidator.Result result = validator.evaluate(
                claim(new BigDecimal("1000")), policy(), history(3), rules, Map.of());

        assertThat(result.fastTrack()).isFalse();
    }

    @Test
    void policyNotUpToDate_doesNotFastTrack() {
        BusinessRules rules = baseRules()
                .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                        .requiresUpToDatePolicy(true)
                        .build())
                .build();

        FastTrackValidator.Result result = validator.evaluate(
                claim(new BigDecimal("1000")), policy(false), history(0), rules, Map.of());

        assertThat(result.fastTrack()).isFalse();
    }

    private ClaimReport claim(BigDecimal claimedAmount) {
        return ClaimReport.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Robo en vía pública")
                .insuredItem("Motorola Edge 50 Pro")
                .insuredId("40.123.456")
                .policyNumber("POL-CEL-2024-001")
                .description("Robo en vía pública.")
                .eventDate(LocalDateTime.of(2026, 6, 13, 19, 45))
                .eventLocation("CABA")
                .claimedAmount(claimedAmount)
                .attachmentsOcr(List.of())
                .build();
    }

    private InsuredPolicy policy() {
        return policy(true);
    }

    private InsuredPolicy policy(boolean upToDate) {
        return InsuredPolicy.builder()
                .policyNumber("POL-CEL-2024-001")
                .insuredName("Laura Fernández")
                .insuredId("40.123.456")
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .effectiveFrom(LocalDate.of(2024, 3, 1))
                .effectiveTo(LocalDate.of(2027, 3, 1))
                .upToDate(upToDate)
                .insuredAmount(new BigDecimal("400000"))
                .deductible(new BigDecimal("50000"))
                .coverages(List.of())
                .applicableClauses(List.of())
                .build();
    }

    private InsuredHistory history(int previousClaimsCount) {
        return InsuredHistory.builder()
                .insuredId("40.123.456")
                .previousClaimsCount(previousClaimsCount)
                .totalAmountClaimed(BigDecimal.ZERO)
                .customerSince(LocalDate.of(2024, 3, 1))
                .claims(List.of())
                .build();
    }

    private BusinessRules.BusinessRulesBuilder baseRules() {
        return BusinessRules.builder()
                .branchId("Celulares")
                .claimCauseId("Robo en vía pública")
                .rules(List.of())
                .exclusions(List.of())
                .fastTrackCriteria(List.of());
    }
}
