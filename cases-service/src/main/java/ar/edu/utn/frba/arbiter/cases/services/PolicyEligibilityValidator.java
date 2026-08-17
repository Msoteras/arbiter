package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyNotEligibleException;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Basic policy validations at case <b>intake</b>: if the event doesn't fall under a contract with
 * coverage, the case doesn't get created. Runs before the case exists, i.e. before the Fast Track
 * gate and before the LLM.
 *
 * <p><b>Why here and not in the engine.</b> These three things aren't "a recommendation to
 * review": they're the nonexistence of the right being claimed. Creating the case just so someone
 * closes it by hand later adds noise to the analyst's queue and delays the response to the
 * insured, who only finds out once someone looks at it. Rejecting at intake returns the reason on
 * the spot.
 *
 * <p>Doesn't contradict human-in-the-loop (CLAUDE.md #5, decision #5): the rule doesn't
 * <b>resolve</b> a case, it prevents one from being born — the same criterion already used to
 * reject a nonexistent or someone-else's policy. Every case that does get created still goes
 * through an analyst, no exception.
 *
 * <p><b>Arrears is opt-in and configurable, not hardcoded here.</b> Whether a policy in arrears
 * blocks the alta at all depends on the referente's {@code POLICY_STANDING} rule
 * ({@code InsurerHardRuleService}, rules-service): {@code onArrears=REJECT} makes
 * {@link #assertPolicyStanding} reject here; {@code STANDBY} (the default, and the prior
 * behavior) leaves arrears as a Fast Track criterion and a scoring factor for
 * {@code TemporalRuleEvaluator} to pick up during classification, same as before this rule
 * existed. Scoped to the whole insurer, not a coverage: a policy is or isn't in arrears
 * regardless of which coverage the claim lands under.
 *
 * <p>With no data to verify, it <b>does not reject</b>: a policy whose dates never arrived from
 * the insurer DB is a sync problem, not an expired contract. The case proceeds, and the coverage
 * window gets looked at again by {@code TemporalRuleEvaluator} during classification (D13), which
 * does derive to the analyst there. The hard cut needs certainty — same reasoning applies if
 * rules-service itself can't be reached: see {@link #assertPolicyStanding}.
 */
@Service
@RequiredArgsConstructor
public class PolicyEligibilityValidator {

    private static final Logger log = LoggerFactory.getLogger(PolicyEligibilityValidator.class);

    private final InsurerAdapter insurerAdapter;
    private final RulesServiceClient rulesServiceClient;

    /**
     * @param policyNumber   only used to look the policy up again against the insurer DB — the
     *                       resolved {@code coverage} already came from it, but arrears/vigencia
     *                       need fields {@code Coverage} doesn't carry.
     */
    public void validate(String policyNumber, LocalDateTime eventDate, LocalDateTime policeReportAt,
                          Coverage coverage) {
        assertCoherentDates(eventDate, policeReportAt);

        PolicyResponse policy = insurerAdapter.findPolicy(policyNumber).orElse(null);
        if (policy == null) {
            // The local policy already resolved (CaseReferenceResolver), so this is a mismatch
            // between the snapshot and the insurer DB. Not a reason to reject the denuncia.
            log.warn("[PolicyEligibility] Policy {} isn't in the insurer DB — coverage window and "
                    + "waiting period aren't validated", policyNumber);
            return;
        }
        assertInForceOnEventDate(eventDate, policy);
        assertOutsideWaitingPeriod(eventDate, policy, coverage);
        assertPolicyStanding(policy);
    }

    /**
     * Arrears, only when the referente turned {@code POLICY_STANDING} on with
     * {@code onArrears=REJECT}. Unlike the coverage-window and waiting-period checks above, this
     * one reads its own switch from rules-service instead of always applying: arrears is a
     * business-policy call ("what do we do when the insured hasn't paid"), not a fact about
     * whether the right being claimed exists.
     *
     * <p><b>rules-service unreachable does NOT reject.</b> Every alta would otherwise hard-depend
     * on rules-service's uptime, even for insurers that never configured this rule — a much wider
     * blast radius than the coverage-window check, which only reads data cases-service already
     * has in hand. Logged as a warning; the case proceeds and, if the policy really is in arrears,
     * the Fast Track criterion and the scoring factor still catch it, same as before this rule
     * existed.
     */
    private void assertPolicyStanding(PolicyResponse policy) {
        RulesServiceClient.PolicyStandingRule rule;
        try {
            rule = rulesServiceClient.policyStandingRule();
        } catch (RestClientException e) {
            log.warn("[PolicyEligibility] Couldn't reach rules-service for the POLICY_STANDING rule — "
                    + "arrears isn't validated at intake for this alta: {}", e.getMessage());
            return;
        }
        if (rule == null || !rule.rejectsAtIntake() || policy.upToDate()) {
            return;
        }
        throw new PolicyNotEligibleException(String.format(
                "La póliza %s tiene un saldo pendiente de pago, así que no se puede iniciar el "
                        + "expediente hasta que se regularice.",
                policy.policyNumber()));
    }

    /**
     * A police report before the event, or after today, isn't "suspicious": it's impossible.
     * Rejected as bad data instead of sent off to be analyzed.
     *
     * <p>The event date isn't checked here: both callers' {@code eventDate} is already
     * {@code @NotNull @PastOrPresent} ({@code CaseRequest}, {@code EligibilityCheckRequest}) and
     * the controller validates the request, so a future date never reaches this point.
     * {@code policeReportAt}, on the other hand, is optional and carries no declarative
     * constraint — it can't have one, because what bounds it is the event date.
     */
    private void assertCoherentDates(LocalDateTime eventDate, LocalDateTime policeReportAt) {
        if (eventDate == null || policeReportAt == null) {
            return;
        }
        if (policeReportAt.isBefore(eventDate)) {
            throw new PolicyNotEligibleException(
                    "La denuncia policial no puede ser anterior al siniestro. Revisá las dos fechas.");
        }
        if (policeReportAt.isAfter(LocalDateTime.now())) {
            throw new PolicyNotEligibleException("La fecha de la denuncia policial no puede ser futura.");
        }
    }

    /** The event has to have occurred while the policy was in force; otherwise no contract covers it. */
    private void assertInForceOnEventDate(LocalDateTime requestedEventDate, PolicyResponse policy) {
        if (requestedEventDate == null || policy.effectiveFrom() == null || policy.effectiveTo() == null) {
            return;
        }
        LocalDate eventDate = requestedEventDate.toLocalDate();
        if (eventDate.isBefore(policy.effectiveFrom()) || eventDate.isAfter(policy.effectiveTo())) {
            throw new PolicyNotEligibleException(String.format(
                    "La póliza %s no estaba vigente el %s (vigencia: %s a %s), así que el siniestro no "
                            + "está cubierto.",
                    policy.policyNumber(), eventDate, policy.effectiveFrom(), policy.effectiveTo()));
        }
    }

    /**
     * Waiting period: the first days since the policy's start date during which there's still no
     * coverage, even though the policy is in force. Exists so an insurance policy can't be bought
     * against an event that already happened or is imminent, and that's why it's a rejection and
     * not just a note: during the waiting period the contract simply doesn't respond.
     */
    private void assertOutsideWaitingPeriod(LocalDateTime requestedEventDate, PolicyResponse policy, Coverage coverage) {
        if (requestedEventDate == null || policy.effectiveFrom() == null
                || coverage == null || coverage.getWaitingPeriodDays() == null) {
            return;
        }
        LocalDate eventDate = requestedEventDate.toLocalDate();
        LocalDate coverageStart = policy.effectiveFrom().plusDays(coverage.getWaitingPeriodDays());
        if (eventDate.isBefore(coverageStart)) {
            throw new PolicyNotEligibleException(String.format(
                    "La cobertura tiene una carencia de %d días desde el alta de la póliza (%s): recién "
                            + "cubre siniestros ocurridos a partir del %s.",
                    coverage.getWaitingPeriodDays(), policy.effectiveFrom(), coverageStart));
        }
    }
}
