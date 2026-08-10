package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Reglas duras temporales (D13 vigencia, D11 plazo de denuncia, D10 tope de eventos). Unit puro. */
class TemporalRuleEvaluatorTest {

    private final TemporalRuleEvaluator evaluator = new TemporalRuleEvaluator();

    private static final LocalDateTime EVENT = LocalDateTime.of(2026, 6, 13, 20, 0);

    private ClaimReport claim(LocalDateTime reportedAt) {
        return ClaimReport.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Robo en vía pública")
                .coverageId(1L)
                .claimCauseId(2L)
                .insuredId("40.123.456")
                .policyNumber("POL-CEL-2024-001")
                .description("...")
                .eventDate(EVENT)
                .reportedAt(reportedAt)
                .build();
    }

    private InsuredPolicy policy(LocalDate from, LocalDate to) {
        return InsuredPolicy.builder()
                .policyNumber("POL-CEL-2024-001")
                .effectiveFrom(from)
                .effectiveTo(to)
                .upToDate(true)
                .insuredAmount(new BigDecimal("400000"))
                .build();
    }

    private InsuredHistory history(List<InsuredHistory.ClaimRecord> claims) {
        return InsuredHistory.builder()
                .insuredId("40.123.456")
                .previousClaimsCount(claims.size())
                .totalAmountClaimed(BigDecimal.ZERO)
                .claims(claims)
                .build();
    }

    private InsuredHistory.ClaimRecord priorClaim(LocalDate date, String branch) {
        return InsuredHistory.ClaimRecord.builder().claimId("x").date(date).branch(branch)
                .claimCause("Robo en vía pública").status("Aprobado").build();
    }

    private BusinessRules rules(Long reportDeadlineHours, Integer maxEventsPerYear) {
        return BusinessRules.builder()
                .branchId("Celulares")
                .rules(List.of()).exclusions(List.of()).fastTrackCriteria(List.of())
                .reportDeadlineHours(reportDeadlineHours)
                .maxEventsPerYear(maxEventsPerYear)
                .build();
    }

    // ── D13 · vigencia ─────────────────────────────────────────────────────
    @Test
    void eventOutsidePolicyPeriod_blocks() {
        var result = evaluator.evaluate(
                claim(null), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 6, 1)), history(List.of()), rules(null, null));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("vigencia"));
    }

    @Test
    void eventWithinPolicyPeriod_ok() {
        var result = evaluator.evaluate(
                claim(null), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)), history(List.of()), rules(null, null));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    // ── D11 · plazo de denuncia ────────────────────────────────────────────
    @Test
    void reportedLate_blocks() {
        // Denuncia 100 hs después del hecho, plazo de la cobertura 72 hs.
        var result = evaluator.evaluate(
                claim(EVENT.plusHours(100)), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)),
                history(List.of()), rules(72L, null));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("fuera de plazo"));
    }

    @Test
    void reportedInTime_ok() {
        var result = evaluator.evaluate(
                claim(EVENT.plusHours(10)), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)),
                history(List.of()), rules(72L, null));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    @Test
    void reportBeforeEvent_blocksAsInconsistent() {
        var result = evaluator.evaluate(
                claim(EVENT.minusHours(5)), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)),
                history(List.of()), rules(72L, null));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("anterior"));
    }

    // ── D10 · tope de eventos por año ──────────────────────────────────────
    @Test
    void exceedsMaxAnnualEvents_blocks() {
        // Tope 1/año, ya hay 1 siniestro en el ramo en los últimos 12 meses → el actual es el 2º.
        var result = evaluator.evaluate(
                claim(null), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)),
                history(List.of(priorClaim(LocalDate.of(2026, 2, 1), "Celulares"))), rules(null, 1));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("tope"));
    }

    @Test
    void withinMaxAnnualEvents_ok() {
        var result = evaluator.evaluate(
                claim(null), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)),
                history(List.of(priorClaim(LocalDate.of(2026, 2, 1), "Celulares"))), rules(null, 2));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    @Test
    void priorClaimOutsideWindowOrOtherBranch_doesNotCount() {
        var result = evaluator.evaluate(
                claim(null), policy(LocalDate.of(2024, 1, 1), LocalDate.of(2027, 1, 1)),
                history(List.of(
                        priorClaim(LocalDate.of(2024, 1, 1), "Celulares"), // fuera de la ventana de 12 meses
                        priorClaim(LocalDate.of(2026, 3, 1), "Hogar"))),    // otro ramo
                rules(null, 1));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    @Test
    void missingData_doesNotBlind_block() {
        // Sin fechas de póliza, sin reportedAt y sin límites configurados: nada que evaluar.
        var result = evaluator.evaluate(claim(null), policy(null, null), history(List.of()), rules(null, null));

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.reasons()).isEmpty();
    }
}
