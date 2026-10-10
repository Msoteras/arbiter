package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyNotEligibleException;
import ar.edu.utn.frba.arbiter.cases.exceptions.RulesUnavailableException;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    /** Every claim cause has a schedule unless a test says otherwise. */
    @BeforeEach
    void givenEveryClaimCauseHasADocumentSchedule() {
        when(rulesServiceClient.requiredDocumentTypes(anyString(), anyString())).thenReturn(List.of("police_report"));
    }

    @Test
    void anEventInsideThePolicyPeriod_isAccepted() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));

        assertThatCode(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null, coverage(null)))
                .doesNotThrowAnyException();
    }

    @Test
    void anEventAfterThePolicyExpired_isRejected() {
        givenPolicy(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 1, 1));

        assertThatThrownBy(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null, coverage(null)))
                .isInstanceOf(PolicyNotEligibleException.class)
                .hasMessageContaining("no estaba vigente");
    }

    @Test
    void anEventBeforeThePolicyStarted_isRejected() {
        givenPolicy(LocalDate.of(2026, 3, 1), LocalDate.of(2027, 3, 1));

        assertThatThrownBy(() -> validate(LocalDateTime.of(2026, 1, 5, 10, 0), null, coverage(null)))
                .isInstanceOf(PolicyNotEligibleException.class)
                .hasMessageContaining("no estaba vigente");
    }

    @Test
    void theLastDayOfThePolicy_isStillCovered() {
        givenPolicy(LocalDate.of(2025, 6, 14), LocalDate.of(2026, 6, 13));

        assertThatCode(() -> validate(LocalDateTime.of(2026, 6, 13, 23, 0), null, coverage(null)))
                .doesNotThrowAnyException();
    }

    @Test
    void anEventInsideTheWaitingPeriod_isRejected() {
        givenPolicy(LocalDate.of(2026, 6, 8), LocalDate.of(2027, 6, 8));

        assertThatThrownBy(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null, coverage(30)))
                .isInstanceOf(PolicyNotEligibleException.class)
                .hasMessageContaining("carencia de 30 días");
    }

    /** The day the waiting period ends is already covered (start 05/14 + 30 days = 06/13). */
    @Test
    void theDayTheWaitingPeriodEnds_isAlreadyCovered() {
        givenPolicy(LocalDate.of(2026, 5, 14), LocalDate.of(2027, 5, 14));

        assertThatCode(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null, coverage(30)))
                .doesNotThrowAnyException();
    }

    @Test
    void withoutAWaitingPeriodConfigured_nothingIsChecked() {
        givenPolicy(LocalDate.of(2026, 6, 12), LocalDate.of(2027, 6, 12));

        assertThatCode(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null, coverage(null)))
                .doesNotThrowAnyException();
    }

    @Test
    void aPoliceReportBeforeTheEvent_isRejected() {
        LocalDateTime event = LocalDateTime.now().minusDays(3);

        assertThatThrownBy(() -> validate(event, event.minusHours(5), coverage(null)))
                .isInstanceOf(PolicyNotEligibleException.class)
                .hasMessageContaining("no puede ser anterior al siniestro");
    }

    @Test
    void aFuturePoliceReport_isRejected() {
        LocalDateTime event = LocalDateTime.now().minusDays(3);

        assertThatThrownBy(() -> validate(event, LocalDateTime.now().plusDays(1), coverage(null)))
                .isInstanceOf(PolicyNotEligibleException.class)
                .hasMessageContaining("denuncia policial no puede ser futura");
    }

    /**
     * A policy that isn't in the insurer DB is a snapshot mismatch, not an expired contract: intake
     * proceeds and the coverage window gets looked at again by the engine. Rejecting here
     * would punish the insured for a sync problem.
     */
    @Test
    void whenTheInsurerDatabaseHasNoPolicy_theClaimIsNotRejected() {
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.empty());

        assertThatCode(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null, coverage(30)))
                .doesNotThrowAnyException();
    }

    @Test
    void withoutPolicyDates_nothingIsChecked() {
        givenPolicy(null, null);

        assertThatCode(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null, coverage(30)))
                .doesNotThrowAnyException();
    }

    @Test
    void aPolicyInArrears_isRejectedWhenTheRuleIsActiveAndOnArrearsIsReject() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), false);
        when(rulesServiceClient.policyStandingRule())
                .thenReturn(new RulesServiceClient.PolicyStandingRule(true, "REJECT"));

        assertThatThrownBy(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null, coverage(null)))
                .isInstanceOf(PolicyNotEligibleException.class)
                .hasMessageContaining("saldo pendiente de pago");
    }

    /** STANDBY is the referent's default: arrears stays a Fast Track criterion and a scoring factor. */
    @Test
    void aPolicyInArrears_isNotRejectedWhenOnArrearsIsStandby() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), false);
        when(rulesServiceClient.policyStandingRule())
                .thenReturn(new RulesServiceClient.PolicyStandingRule(true, "STANDBY"));

        assertThatCode(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null, coverage(null)))
                .doesNotThrowAnyException();
    }

    @Test
    void aPolicyInArrears_isNotRejectedWhenTheRuleIsInactive() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), false);
        when(rulesServiceClient.policyStandingRule())
                .thenReturn(new RulesServiceClient.PolicyStandingRule(false, null));

        assertThatCode(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null, coverage(null)))
                .doesNotThrowAnyException();
    }

    @Test
    void aPolicyUpToDate_isNeverRejectedEvenWithOnArrearsReject() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), true);
        when(rulesServiceClient.policyStandingRule())
                .thenReturn(new RulesServiceClient.PolicyStandingRule(true, "REJECT"));

        assertThatCode(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null, coverage(null)))
                .doesNotThrowAnyException();
    }

    /**
     * rules-service unreachable does NOT reject: every claim intake would otherwise hard-depend on its
     * uptime, even for insurers that never configured this rule. The policy really being in
     * arrears still gets caught by the Fast Track criterion and the scoring factor downstream.
     */
    @Test
    void whenRulesServiceIsUnreachable_arrearsIsNotValidated() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), false);
        when(rulesServiceClient.policyStandingRule())
                .thenThrow(new RulesUnavailableException(new IllegalStateException("rules-service down")));

        assertThatCode(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null, coverage(null)))
                .doesNotThrowAnyException();
    }

    @Test
    void aClaimCauseExcludedByTheCoverage_isRejected() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of(3L));

        assertThatThrownBy(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null,
                coverage(null, 1L, "Robo de celular"), claimCause(3L, "Hurto")))
                .isInstanceOf(PolicyNotEligibleException.class)
                .hasMessageContaining("no cubre");
    }

    @Test
    void aClaimCauseNotExcluded_isAccepted() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of(3L));

        assertThatCode(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null,
                coverage(null, 1L, "Robo de celular"), claimCause(2L, "Robo en vía pública")))
                .doesNotThrowAnyException();
    }

    /** The eligibility precheck runs in the wizard before a claimCause is chosen. */
    @Test
    void withoutAClaimCause_theExclusionIsNotChecked() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));

        assertThatCode(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null,
                coverage(null, 1L, "Robo de celular")))
                .doesNotThrowAnyException();
        verify(rulesServiceClient, never()).excludedClaimCauseIds(any());
    }

    /**
     * rules-service unreachable does NOT reject: same fail-open reasoning as
     * {@code whenRulesServiceIsUnreachable_arrearsIsNotValidated} above.
     */
    @Test
    void whenRulesServiceIsUnreachableForExclusions_theClaimIsNotRejected() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenThrow(new RulesUnavailableException(new IllegalStateException("rules-service down")));

        assertThatCode(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null,
                coverage(null, 1L, "Robo de celular"), claimCause(3L, "Hurto")))
                .doesNotThrowAnyException();
    }

    /** Without a schedule the case would count as complete without asking for a single document. */
    @Test
    void aClaimCauseWithoutADocumentSchedule_isRejected() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
        when(rulesServiceClient.requiredDocumentTypes("Celulares", "Caída")).thenReturn(List.of());

        assertThatThrownBy(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null,
                coverage(null, 1L, "Robo de celular"), claimCause(4L, "Caída")))
                .isInstanceOf(PolicyNotEligibleException.class)
                .hasMessage("Todavía no se pueden denunciar siniestros por \"Caída\". Comunicate con tu aseguradora.");
    }

    /** Null is "couldn't read it", not "not configured": fails open like the other checks. */
    @Test
    void whenTheDocumentScheduleCantBeRead_theClaimIsNotRejected() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
        when(rulesServiceClient.requiredDocumentTypes("Celulares", "Caída")).thenReturn(null);

        assertThatCode(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null,
                coverage(null, 1L, "Robo de celular"), claimCause(4L, "Caída")))
                .doesNotThrowAnyException();
    }

    @Test
    void withoutAClaimCause_theDocumentScheduleIsNotChecked() {
        givenPolicy(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));

        assertThatCode(() -> validate(LocalDateTime.of(2026, 6, 13, 20, 0), null, coverage(null)))
                .doesNotThrowAnyException();
        verify(rulesServiceClient, never()).requiredDocumentTypes(any(), any());
    }

    private void validate(LocalDateTime eventDate, LocalDateTime policeReportAt, Coverage coverage) {
        validate(eventDate, policeReportAt, coverage, null);
    }

    private void validate(LocalDateTime eventDate, LocalDateTime policeReportAt, Coverage coverage, ClaimCause claimCause) {
        validator.validate(POLICY_NUMBER, eventDate, policeReportAt, coverage, claimCause);
    }

    private void givenPolicy(LocalDate from, LocalDate to) {
        givenPolicy(from, to, true);
    }

    private void givenPolicy(LocalDate from, LocalDate to, boolean upToDate) {
        // effectiveTo goes to the end of the day, not the start: these fixtures are date-only
        // (no hour precision needed for what each test is checking), and theLastDayOfThePolicy_
        // isStillCovered relies on the last calendar day still counting as covered.
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.of(PolicyResponse.builder()
                .policyNumber(POLICY_NUMBER)
                .effectiveFrom(from == null ? null : from.atStartOfDay())
                .effectiveTo(to == null ? null : to.atTime(23, 59, 59))
                .upToDate(upToDate)
                .build()));
    }

    private Coverage coverage(Integer waitingPeriodDays) {
        return coverage(waitingPeriodDays, null, null);
    }

    private Coverage coverage(Integer waitingPeriodDays, Long id, String name) {
        Coverage coverage = new Coverage();
        coverage.setId(id);
        coverage.setName(name);
        coverage.setWaitingPeriodDays(waitingPeriodDays);
        return coverage;
    }

    private ClaimCause claimCause(Long id, String name) {
        return ClaimCause.builder().id(id).name(name).branch(Branch.builder().id(1L).name("Celulares").build()).build();
    }
}
