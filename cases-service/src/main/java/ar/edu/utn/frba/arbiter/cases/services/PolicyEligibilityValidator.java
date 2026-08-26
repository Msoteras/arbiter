package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyNotEligibleException;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

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

    /** Para los mensajes al asegurado: la vigencia lleva hora, y el mensaje tiene que mostrarla. */
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final InsurerAdapter insurerAdapter;
    private final RulesServiceClient rulesServiceClient;

    /**
     * @param policyNumber   only used to look the policy up again against the insurer DB — the
     *                       resolved {@code coverage} already came from it, but arrears/vigencia
     *                       need fields {@code Coverage} doesn't carry.
     * @param claimCause     the declared hecho generador. Nullable: the eligibility precheck
     *                       (step 1/2 of the wizard, before "¿Qué te pasó?" is even asked) doesn't
     *                       have one yet, so it skips {@link #assertCoverageIncludesClaimCause} —
     *                       only the real {@code POST /cases} always has it and always runs the
     *                       check.
     */
    public void validate(String policyNumber, LocalDateTime eventDate, LocalDateTime policeReportAt,
                          Coverage coverage, ClaimCause claimCause) {
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
        assertCoverageIncludesClaimCause(claimCause, coverage);
    }

    /**
     * The wizard already filters "¿Qué te pasó?" so an excluded hecho generador never shows up,
     * but that's a UI convenience — a client that posts
     * straight to {@code POST /cases} skipping the dropdown was never stopped by anything server
     * side. A coverage that explicitly doesn't cover a hecho generador is the same category as
     * vigencia/carencia/mora above: not a matter for the LLM to weigh in on, the right being
     * claimed doesn't exist for that cause. {@code COVERAGE_EXCLUSION} is a blacklist — no row
     * (or an unreachable rules-service) means nothing is excluded, same "fail open" as
     * {@link #assertPolicyStanding} and for the same reason: a rules-service blip shouldn't turn
     * into a hard-down for every alta.
     *
     * <p>Doesn't replace {@code CoverageRuleEvaluator} in classification-service — that one still
     * runs during classification and leaves the audited {@code rule_result} row (D3/D4c). This is
     * the same fact checked earlier, at intake, so a claim that's provably not covered doesn't sit
     * in an analyst's queue in the meantime.
     */
    private void assertCoverageIncludesClaimCause(ClaimCause claimCause, Coverage coverage) {
        if (claimCause == null || coverage == null || coverage.getId() == null) {
            return;
        }
        List<Long> excludedIds;
        try {
            excludedIds = rulesServiceClient.excludedClaimCauseIds(coverage.getId());
        } catch (RestClientException e) {
            log.warn("[PolicyEligibility] Couldn't reach rules-service for COVERAGE_EXCLUSION — "
                    + "coverage match isn't validated at intake for this alta: {}", e.getMessage());
            return;
        }
        if (!excludedIds.contains(claimCause.getId())) {
            return;
        }
        throw new PolicyNotEligibleException(String.format(
                "La cobertura \"%s\" no cubre \"%s\", así que no se puede iniciar el expediente.",
                coverage.getName(), claimCause.getName()));
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

    /**
     * The event has to have occurred while the policy was in force; otherwise no contract covers it.
     *
     * <p>Compared as a full timestamp, not truncated to the day: the reference policy fixes
     * vigencia with an exact hour ("desde las 12:00 hs del..."), and comparing only by date gave
     * false accepts at the edge — an event two hours before coverage started, same calendar day,
     * used to pass. {@code policy.effectiveFrom()/effectiveTo()} already carry the hour
     * ({@code aseguradora_*.poliza.vigencia_desde/hasta} is {@code timestamptz}).
     */
    private void assertInForceOnEventDate(LocalDateTime requestedEventDate, PolicyResponse policy) {
        if (requestedEventDate == null || policy.effectiveFrom() == null || policy.effectiveTo() == null) {
            return;
        }
        if (requestedEventDate.isBefore(policy.effectiveFrom()) || requestedEventDate.isAfter(policy.effectiveTo())) {
            throw new PolicyNotEligibleException(String.format(
                    "La póliza %s no estaba vigente el %s (vigencia: %s a %s), así que el siniestro no "
                            + "está cubierto.",
                    policy.policyNumber(), DISPLAY_FORMAT.format(requestedEventDate),
                    DISPLAY_FORMAT.format(policy.effectiveFrom()), DISPLAY_FORMAT.format(policy.effectiveTo())));
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
        LocalDateTime coverageStart = policy.effectiveFrom().plusDays(coverage.getWaitingPeriodDays());
        if (requestedEventDate.isBefore(coverageStart)) {
            throw new PolicyNotEligibleException(String.format(
                    "La cobertura tiene una carencia de %d días desde el alta de la póliza (%s): recién "
                            + "cubre siniestros ocurridos a partir del %s.",
                    coverage.getWaitingPeriodDays(), DISPLAY_FORMAT.format(policy.effectiveFrom()),
                    DISPLAY_FORMAT.format(coverageStart)));
        }
    }
}
