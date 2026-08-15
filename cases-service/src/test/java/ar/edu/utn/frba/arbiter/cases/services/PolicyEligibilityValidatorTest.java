package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyNotEligibleException;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Basic policy validations at intake: coverage window, waiting period, and date coherence. What
 * fails here never becomes a case, so what passes is what later reaches the analyst.
 */
class PolicyEligibilityValidatorTest {

    private static final String POLICY_NUMBER = "POL-CEL-2026-042";

    private final InsurerAdapter insurerAdapter = mock(InsurerAdapter.class);
    private final RulesServiceClient rulesServiceClient = mock(RulesServiceClient.class);
    private final PolicyEligibilityValidator validator = new PolicyEligibilityValidator(insurerAdapter, rulesServiceClient);

    // ── Vigencia ───────────────────────────────────────────────────────────

    @Test
    void anEventInsideThePolicyPeriod_isAccepted() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));

        assertThatCode(() -> validator.validate(request(LocalDateTime.of(2026, 6, 13, 20, 0), null), coverage(null)))
                .doesNotThrowAnyException();
    }

    @Test
    void anEventAfterThePolicyExpired_isRejected() {
        givenPolicy(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 1, 1));

        assertThatThrownBy(() -> validator.validate(request(LocalDateTime.of(2026, 6, 13, 20, 0), null), coverage(null)))
                .isInstanceOf(PolicyNotEligibleException.class)
                .hasMessageContaining("no estaba vigente");
    }

    @Test
    void anEventBeforeThePolicyStarted_isRejected() {
        givenPolicy(LocalDate.of(2026, 3, 1), LocalDate.of(2027, 3, 1));

        assertThatThrownBy(() -> validator.validate(request(LocalDateTime.of(2026, 1, 5, 10, 0), null), coverage(null)))
                .isInstanceOf(PolicyNotEligibleException.class)
                .hasMessageContaining("no estaba vigente");
    }

    /** The last day of coverage still covers. */
    @Test
    void theLastDayOfThePolicy_isStillCovered() {
        givenPolicy(LocalDate.of(2025, 6, 14), LocalDate.of(2026, 6, 13));

        assertThatCode(() -> validator.validate(request(LocalDateTime.of(2026, 6, 13, 23, 0), null), coverage(null)))
                .doesNotThrowAnyException();
    }

    // ── Carencia ───────────────────────────────────────────────────────────

    @Test
    void anEventInsideTheWaitingPeriod_isRejected() {
        givenPolicy(LocalDate.of(2026, 6, 8), LocalDate.of(2027, 6, 8));

        assertThatThrownBy(() -> validator.validate(request(LocalDateTime.of(2026, 6, 13, 20, 0), null), coverage(30)))
                .isInstanceOf(PolicyNotEligibleException.class)
                .hasMessageContaining("carencia de 30 días");
    }

    /** The day the waiting period ends is already covered (start 05/14 + 30 days = 06/13). */
    @Test
    void theDayTheWaitingPeriodEnds_isAlreadyCovered() {
        givenPolicy(LocalDate.of(2026, 5, 14), LocalDate.of(2027, 5, 14));

        assertThatCode(() -> validator.validate(request(LocalDateTime.of(2026, 6, 13, 20, 0), null), coverage(30)))
                .doesNotThrowAnyException();
    }

    @Test
    void withoutAWaitingPeriodConfigured_nothingIsChecked() {
        givenPolicy(LocalDate.of(2026, 6, 12), LocalDate.of(2027, 6, 12));

        assertThatCode(() -> validator.validate(request(LocalDateTime.of(2026, 6, 13, 20, 0), null), coverage(null)))
                .doesNotThrowAnyException();
    }

    // ── Coherencia de fechas ───────────────────────────────────────────────

    @Test
    void aPoliceReportBeforeTheEvent_isRejected() {
        LocalDateTime event = LocalDateTime.now().minusDays(3);

        assertThatThrownBy(() -> validator.validate(request(event, event.minusHours(5)), coverage(null)))
                .isInstanceOf(PolicyNotEligibleException.class)
                .hasMessageContaining("no puede ser anterior al siniestro");
    }

    @Test
    void aFuturePoliceReport_isRejected() {
        LocalDateTime event = LocalDateTime.now().minusDays(3);

        assertThatThrownBy(() -> validator.validate(request(event, LocalDateTime.now().plusDays(1)), coverage(null)))
                .isInstanceOf(PolicyNotEligibleException.class)
                .hasMessageContaining("denuncia policial no puede ser futura");
    }

    // ── Sin datos para verificar ───────────────────────────────────────────

    /**
     * A policy that isn't in the insurer DB is a snapshot mismatch, not an expired contract: intake
     * proceeds and the coverage window gets looked at again by the engine (D13). Rejecting here
     * would punish the insured for a sync problem.
     */
    @Test
    void whenTheInsurerDatabaseHasNoPolicy_theClaimIsNotRejected() {
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.empty());

        assertThatCode(() -> validator.validate(request(LocalDateTime.of(2026, 6, 13, 20, 0), null), coverage(30)))
                .doesNotThrowAnyException();
    }

    @Test
    void withoutPolicyDates_nothingIsChecked() {
        givenPolicy(null, null);

        assertThatCode(() -> validator.validate(request(LocalDateTime.of(2026, 6, 13, 20, 0), null), coverage(30)))
                .doesNotThrowAnyException();
    }

    // ── Mora ───────────────────────────────────────────────────────────────

    @Test
    void aPolicyInArrears_isRejectedWhenTheRuleIsActiveAndOnArrearsIsReject() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), false);
        when(rulesServiceClient.policyStandingRule())
                .thenReturn(new RulesServiceClient.PolicyStandingRule(true, "REJECT"));

        assertThatThrownBy(() -> validator.validate(request(LocalDateTime.of(2026, 6, 13, 20, 0), null), coverage(null)))
                .isInstanceOf(PolicyNotEligibleException.class)
                .hasMessageContaining("saldo pendiente de pago");
    }

    /** STANDBY is the referente's default: arrears stays a Fast Track criterion and a scoring factor. */
    @Test
    void aPolicyInArrears_isNotRejectedWhenOnArrearsIsStandby() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), false);
        when(rulesServiceClient.policyStandingRule())
                .thenReturn(new RulesServiceClient.PolicyStandingRule(true, "STANDBY"));

        assertThatCode(() -> validator.validate(request(LocalDateTime.of(2026, 6, 13, 20, 0), null), coverage(null)))
                .doesNotThrowAnyException();
    }

    @Test
    void aPolicyInArrears_isNotRejectedWhenTheRuleIsInactive() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), false);
        when(rulesServiceClient.policyStandingRule())
                .thenReturn(new RulesServiceClient.PolicyStandingRule(false, null));

        assertThatCode(() -> validator.validate(request(LocalDateTime.of(2026, 6, 13, 20, 0), null), coverage(null)))
                .doesNotThrowAnyException();
    }

    @Test
    void aPolicyUpToDate_isNeverRejectedEvenWithOnArrearsReject() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), true);
        when(rulesServiceClient.policyStandingRule())
                .thenReturn(new RulesServiceClient.PolicyStandingRule(true, "REJECT"));

        assertThatCode(() -> validator.validate(request(LocalDateTime.of(2026, 6, 13, 20, 0), null), coverage(null)))
                .doesNotThrowAnyException();
    }

    /**
     * rules-service unreachable does NOT reject: every alta would otherwise hard-depend on its
     * uptime, even for insurers that never configured this rule. The policy really being in
     * arrears still gets caught by the Fast Track criterion and the scoring factor downstream.
     */
    @Test
    void whenRulesServiceIsUnreachable_arrearsIsNotValidated() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), false);
        when(rulesServiceClient.policyStandingRule())
                .thenThrow(new ResourceAccessException("Connection refused"));

        assertThatCode(() -> validator.validate(request(LocalDateTime.of(2026, 6, 13, 20, 0), null), coverage(null)))
                .doesNotThrowAnyException();
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private void givenPolicy(LocalDate from, LocalDate to) {
        givenPolicy(from, to, true);
    }

    private void givenPolicy(LocalDate from, LocalDate to, boolean upToDate) {
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.of(PolicyResponse.builder()
                .policyNumber(POLICY_NUMBER)
                .effectiveFrom(from)
                .effectiveTo(to)
                .upToDate(upToDate)
                .build()));
    }

    private CaseRequest request(LocalDateTime eventDate, LocalDateTime policeReportAt) {
        return new CaseRequest(
                "Celulares", "Celular Protegido Premium", "Robo en vía pública",
                "Samsung Galaxy S25 Ultra", "42.987.654", POLICY_NUMBER, "...",
                eventDate, "Av. Corrientes 1234", policeReportAt,
                new BigDecimal("285000"), false, true, null, null);
    }

    private Coverage coverage(Integer waitingPeriodDays) {
        Coverage coverage = new Coverage();
        coverage.setWaitingPeriodDays(waitingPeriodDays);
        return coverage;
    }
}
