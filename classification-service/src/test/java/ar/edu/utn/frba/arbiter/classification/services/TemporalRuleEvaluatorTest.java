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

/**
 * Reglas duras temporales (D13 vigencia, D9 carencia, D11 plazo de denuncia, D10 tope de eventos).
 * Unit puro.
 */
class TemporalRuleEvaluatorTest {

    /** 72 h is the property's default; the police deadline is provisional until it's configurable. */
    private final TemporalRuleEvaluator evaluator = new TemporalRuleEvaluator(72);

    private static final LocalDateTime EVENT = LocalDateTime.of(2026, 6, 13, 20, 0);

    private ClaimReport claimWithPoliceReport(LocalDateTime policeReportAt) {
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
                .policeReportAt(policeReportAt)
                .build();
    }

    // ─── D12 · plazo de la denuncia policial (72 hs provisorias) ──────────────────
    // The event is on 13/06/2026 at 20:00.

    @Test
    void aPoliceReportWithinTheDeadline_doesNotBlock() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claimWithPoliceReport(EVENT.plusHours(30)),
                policy(LocalDate.of(2024, 3, 1), LocalDate.of(2027, 3, 1)),
                history(List.of()),
                rules(null, null));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    @Test
    void aPoliceReportPastTheDeadline_blocksFastTrack() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claimWithPoliceReport(EVENT.plusHours(100)),
                policy(LocalDate.of(2024, 3, 1), LocalDate.of(2027, 3, 1)),
                history(List.of()),
                rules(null, null));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("Denuncia policial fuera de plazo"));
    }

    /** Filing with the police before the event is inconsistent data, not a deadline met. */
    @Test
    void aPoliceReportBeforeTheEvent_blocksFastTrack() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claimWithPoliceReport(EVENT.minusDays(2)),
                policy(LocalDate.of(2024, 3, 1), LocalDate.of(2027, 3, 1)),
                history(List.of()),
                rules(null, null));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("anterior a la fecha del hecho"));
    }

    /** "No hubo denuncia policial" es legítimo: la regla no participa, no bloquea a ciegas. */
    @Test
    void withoutADeclaredPoliceReport_theRuleDoesNotParticipate() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claimWithPoliceReport(null),
                policy(LocalDate.of(2024, 3, 1), LocalDate.of(2027, 3, 1)),
                history(List.of()),
                rules(null, null));

        assertThat(result.blocksFastTrack()).isFalse();
    }

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

    private BusinessRules rulesWithWaitingPeriod(Integer waitingPeriodDays) {
        return BusinessRules.builder()
                .branchId("Celulares")
                .rules(List.of()).exclusions(List.of()).fastTrackCriteria(List.of())
                .waitingPeriodDays(waitingPeriodDays)
                .build();
    }

    // ─── D9 · carencia ────────────────────────────────────────────────────────────
    // The event is on 13/06/2026. The waiting period counts from the policy's start.

    /** A 5-day-old policy with a 30-day waiting period: there's a contract, but no cover yet. */
    @Test
    void eventInsideTheWaitingPeriod_blocksFastTrack() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claim(EVENT.plusHours(2)),
                policy(LocalDate.of(2026, 6, 8), LocalDate.of(2027, 6, 8)),
                history(List.of()),
                rulesWithWaitingPeriod(30));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("dentro de la carencia de 30 días"));
    }

    /** Mismo caso, un día después de cumplirse la carencia: cubierto. */
    @Test
    void eventAfterTheWaitingPeriod_doesNotBlock() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claim(EVENT.plusHours(2)),
                policy(LocalDate.of(2026, 5, 1), LocalDate.of(2027, 5, 1)),
                history(List.of()),
                rulesWithWaitingPeriod(30));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** The exact day the waiting period ends is already covered (start 14/05 + 30 days = 13/06). */
    @Test
    void theDayTheWaitingPeriodEnds_isAlreadyCovered() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claim(EVENT.plusHours(2)),
                policy(LocalDate.of(2026, 5, 14), LocalDate.of(2027, 5, 14)),
                history(List.of()),
                rulesWithWaitingPeriod(30));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** Sin carencia configurada la regla no participa — no bloquea a ciegas. */
    @Test
    void withoutAWaitingPeriodConfigured_theRuleDoesNotParticipate() {
        TemporalRuleEvaluator.Result result = evaluator.evaluate(
                claim(EVENT.plusHours(2)),
                policy(LocalDate.of(2026, 6, 8), LocalDate.of(2027, 6, 8)),
                history(List.of()),
                rulesWithWaitingPeriod(null));

        assertThat(result.blocksFastTrack()).isFalse();
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
        // Reported 100 h after the event, coverage deadline 72 h.
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
        // Cap 1/year, there's already 1 claim in the branch in the last 12 months → this is the 2nd.
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
        // No policy dates, no reportedAt and no limits configured: nothing to evaluate.
        var result = evaluator.evaluate(claim(null), policy(null, null), history(List.of()), rules(null, null));

        assertThat(result.blocksFastTrack()).isFalse();
        assertThat(result.reasons()).isEmpty();
    }
}
