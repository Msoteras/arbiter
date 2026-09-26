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
 * Intake policy checks: an event outside a contract with coverage never becomes a case. It resolves
 * nothing, so the human in the loop stays; it just tells the insured on the spot. Fails open: with no
 * data or rules-service down, the case proceeds and classification looks again.
 */
@Service
@RequiredArgsConstructor
public class PolicyEligibilityValidator {

    private static final Logger log = LoggerFactory.getLogger(PolicyEligibilityValidator.class);

    /** Policy terms carry an hour, and the insured-facing message has to show it. */
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final InsurerAdapter insurerAdapter;
    private final RulesServiceClient rulesServiceClient;

    /** @param claimCause null during the wizard's precheck, which runs before the cause is asked */
    public void validate(String policyNumber, LocalDateTime eventDate, LocalDateTime policeReportAt,
                          Coverage coverage, ClaimCause claimCause) {
        assertCoherentDates(eventDate, policeReportAt);

        PolicyResponse policy = insurerAdapter.findPolicy(policyNumber).orElse(null);
        if (policy == null) {
            // A snapshot/insurer DB mismatch, not a reason to reject the claim.
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
     * The wizard already hides excluded causes, but a direct {@code POST /cases} must be stopped too.
     * {@code CoverageRuleEvaluator} still leaves the audited rule result later.
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
     * Arrears only rejects when the referent configured {@code POLICY_STANDING} with
     * {@code onArrears=REJECT}; otherwise it is left to Fast Track and scoring. An unreachable
     * rules-service doesn't reject, or every filing would depend on its uptime.
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
     * A police report before the event, or in the future, is impossible rather than suspicious.
     * The event date itself is already bounded by {@code @PastOrPresent} on the request.
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
     * Compared as a full timestamp, not by day: policy terms start at an exact hour, and an event a
     * few hours before coverage started on the same day must not pass.
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
     * During the waiting period the contract doesn't respond at all, so it is a rejection: it keeps
     * a policy from being bought against an event that already happened.
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
