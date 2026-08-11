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

    /**
     * D14 · el caso que motivó el campo. Sin ventana, "máximo 1 siniestro previo" se compara contra
     * el histórico completo, así que dos siniestros de 2024 dejan afuera del Fast Track a un cliente
     * que hace dos años no reclama.
     */
    @Test
    void withoutWindow_priorClaimsCountTheWholeHistory() {
        BusinessRules rules = baseRules()
                .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                        .maxPriorClaims(1)
                        .build())
                .build();

        FastTrackValidator.Result result = validator.evaluate(
                claim(new BigDecimal("1000")), policy(),
                historyWithClaimsOn(LocalDate.of(2024, 4, 1), LocalDate.of(2024, 8, 1)),
                rules, Map.of());

        assertThat(result.fastTrack()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("Claims previos (2)"));
    }

    /** Con ventana de 12 meses, esos mismos dos siniestros de 2024 ya no cuentan. */
    @Test
    void withWindow_onlyPriorClaimsInsideItCount() {
        BusinessRules rules = baseRules()
                .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                        .maxPriorClaims(1)
                        .priorClaimsWindowMonths(12)
                        .build())
                .build();

        FastTrackValidator.Result result = validator.evaluate(
                claim(new BigDecimal("1000")), policy(),
                historyWithClaimsOn(LocalDate.of(2024, 4, 1), LocalDate.of(2024, 8, 1)),
                rules, Map.of());

        assertThat(result.fastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("en los últimos 12 meses"));
    }

    /** La ventana se cuenta desde el hecho, no desde hoy: un siniestro de hace 3 meses sí entra. */
    @Test
    void theWindowIsCountedBackFromTheEvent() {
        BusinessRules rules = baseRules()
                .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                        .maxPriorClaims(0)
                        .priorClaimsWindowMonths(12)
                        .build())
                .build();

        FastTrackValidator.Result result = validator.evaluate(
                claim(new BigDecimal("1000")), policy(),
                historyWithClaimsOn(LocalDate.of(2026, 3, 10)),
                rules, Map.of());

        assertThat(result.fastTrack()).isFalse();
    }

    /** D14 · antigüedad mínima de la póliza (alta 01/03/2024, hecho 13/06/2026 ⇒ 27 meses). */
    @Test
    void policyOlderThanTheMinimum_fastTracks() {
        BusinessRules rules = baseRules()
                .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                        .minPolicyAgeMonths(6)
                        .build())
                .build();

        FastTrackValidator.Result result = validator.evaluate(
                claim(new BigDecimal("1000")), policy(), history(0), rules, Map.of());

        assertThat(result.fastTrack()).isTrue();
    }

    @Test
    void policyYoungerThanTheMinimum_doesNotFastTrack() {
        BusinessRules rules = baseRules()
                .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                        .minPolicyAgeMonths(36)
                        .build())
                .build();

        FastTrackValidator.Result result = validator.evaluate(
                claim(new BigDecimal("1000")), policy(), history(0), rules, Map.of());

        assertThat(result.fastTrack()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("por debajo del mínimo"));
    }

    /** El Fast Track solo procede sobre lo verificable: sin fecha de alta no se asume antigüedad. */
    @Test
    void withoutPolicyStartDate_theMinimumAgeCannotBeAsserted() {
        BusinessRules rules = baseRules()
                .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                        .minPolicyAgeMonths(6)
                        .build())
                .build();
        InsuredPolicy noStartDate = InsuredPolicy.builder()
                .policyNumber("POL-CEL-2024-001")
                .insuredId("40.123.456")
                .upToDate(true)
                .insuredAmount(new BigDecimal("400000"))
                .coverages(List.of())
                .applicableClauses(List.of())
                .build();

        FastTrackValidator.Result result = validator.evaluate(
                claim(new BigDecimal("1000")), noStartDate, history(0), rules, Map.of());

        assertThat(result.fastTrack()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("No se pudo determinar la antigüedad"));
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

    /** Historial con siniestros fechados, para poder probar la ventana (el hecho es 13/06/2026). */
    private InsuredHistory historyWithClaimsOn(LocalDate... dates) {
        return InsuredHistory.builder()
                .insuredId("40.123.456")
                .previousClaimsCount(dates.length)
                .totalAmountClaimed(BigDecimal.ZERO)
                .customerSince(LocalDate.of(2024, 3, 1))
                .claims(List.of(dates).stream()
                        .map(date -> InsuredHistory.ClaimRecord.builder()
                                .claimId("H-" + date)
                                .date(date)
                                .branch("Celulares")
                                .claimCause("Robo en vía pública")
                                .status("LIQUIDADO")
                                .build())
                        .toList())
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
