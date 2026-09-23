package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Hard temporal rules evaluated in code. Each runs only if the insurer has its {@code insurer_rule}
 * row active, and only with the data to evaluate it (missing data never blocks blindly).
 *
 * <p>The row is the switch; the threshold is a {@code coverage} column (contract terms), except for
 * {@code POLICE_DEADLINE}, which carries its own {@code deadlineHours}, and {@code POLICY_STANDING},
 * which needs none. Failures block Fast Track and add reasons, never close the case; every evaluated
 * rule leaves an auditable {@link RuleFinding}.
 */
@Service
public class TemporalRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TemporalRuleEvaluator.class);

    /** Policy validity carries a time of day, so it's displayed. */
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public record Result(boolean blocksFastTrack, List<String> reasons, List<RuleFinding> findings) {

        public static Result empty() {
            return new Result(false, List.of(), List.of());
        }
    }

    public Result evaluate(ClaimReport claim, InsuredPolicy policy, InsuredHistory history, BusinessRules rules) {
        Map<RuleType, BusinessRules.EvaluableRule> active = activeRules(rules);
        if (active.isEmpty()) {
            return Result.empty();
        }

        Outcome outcome = new Outcome();
        evaluatePolicyInForce(active.get(RuleType.POLICY_IN_FORCE), claim, policy, outcome);
        evaluateWaitingPeriod(active.get(RuleType.WAITING_PERIOD), claim, policy, rules, outcome);
        evaluateReportDeadline(active.get(RuleType.REPORT_DEADLINE), claim, rules, outcome);
        evaluatePoliceReportDeadline(active.get(RuleType.POLICE_DEADLINE), claim, outcome);
        evaluateMaxAnnualEvents(active.get(RuleType.MAX_EVENTS_YEAR), claim, history, rules, outcome);
        evaluatePolicyStanding(active.get(RuleType.POLICY_STANDING), policy, outcome);

        if (outcome.blocksFastTrack) {
            log.info("[TemporalRuleEvaluator] Temporal rules failed (blocking Fast Track): {}",
                    outcome.reasons);
        }
        return new Result(outcome.blocksFastTrack, List.copyOf(outcome.reasons), List.copyOf(outcome.findings));
    }

    /** An unknown type is discarded rather than failing: the engine may be older than the panel's catalog. */
    private Map<RuleType, BusinessRules.EvaluableRule> activeRules(BusinessRules rules) {
        Map<RuleType, BusinessRules.EvaluableRule> active = new EnumMap<>(RuleType.class);
        if (rules.evaluableRules() == null) {
            return active;
        }
        for (BusinessRules.EvaluableRule rule : rules.evaluableRules()) {
            RuleType type = parse(rule.ruleType());
            if (type != null && RuleType.temporalRules().contains(type)) {
                active.put(type, rule);
            }
        }
        return active;
    }

    private RuleType parse(String ruleType) {
        try {
            return ruleType == null ? null : RuleType.valueOf(ruleType);
        } catch (IllegalArgumentException e) {
            log.warn("[TemporalRuleEvaluator] Unknown rule type, ignored: {}", ruleType);
            return null;
        }
    }

    /** Full timestamp, not day: coverage starts at an exact hour. */
    private void evaluatePolicyInForce(
            BusinessRules.EvaluableRule rule, ClaimReport claim, InsuredPolicy policy, Outcome outcome) {
        if (rule == null || claim.eventDate() == null
                || policy.effectiveFrom() == null || policy.effectiveTo() == null) {
            return;
        }
        LocalDateTime eventDate = claim.eventDate();
        outcome.record(rule, policy.inForceOn(eventDate),
                "eventDate=" + DISPLAY_FORMAT.format(eventDate) + " coverageWindow="
                        + DISPLAY_FORMAT.format(policy.effectiveFrom()) + ".."
                        + DISPLAY_FORMAT.format(policy.effectiveTo()),
                String.format("El siniestro (%s) ocurrió fuera de la vigencia de la póliza (%s a %s)",
                        DISPLAY_FORMAT.format(eventDate), DISPLAY_FORMAT.format(policy.effectiveFrom()),
                        DISPLAY_FORMAT.format(policy.effectiveTo())));
    }

    /**
     * Not the same as being in force (there was a contract, but it didn't cover yet) nor as Fast
     * Track's {@code minPolicyAgeMonths}, which decides the path, not the right to coverage.
     */
    private void evaluateWaitingPeriod(
            BusinessRules.EvaluableRule rule, ClaimReport claim, InsuredPolicy policy,
            BusinessRules rules, Outcome outcome) {
        if (rule == null || rules.waitingPeriodDays() == null
                || claim.eventDate() == null || policy.effectiveFrom() == null) {
            return;
        }
        LocalDateTime eventDate = claim.eventDate();
        LocalDateTime coverageStart = policy.effectiveFrom().plusDays(rules.waitingPeriodDays());
        outcome.record(rule, !eventDate.isBefore(coverageStart),
                "eventDate=" + DISPLAY_FORMAT.format(eventDate) + " waitingPeriod="
                        + rules.waitingPeriodDays() + "d from " + DISPLAY_FORMAT.format(policy.effectiveFrom()),
                String.format("El siniestro (%s) ocurrió dentro del período de carencia de %d días: "
                                + "la cobertura recién rige desde el %s (la póliza empezó el %s)",
                        DISPLAY_FORMAT.format(eventDate), rules.waitingPeriodDays(),
                        DISPLAY_FORMAT.format(coverageStart), DISPLAY_FORMAT.format(policy.effectiveFrom())));
    }

    /**
     * Against the <b>declared</b> date; a mismatch with the certificate is DocumentInconsistencyEvaluator's.
     * Without a declared report the rule doesn't take part: not every claim cause requires one.
     */
    private void evaluatePoliceReportDeadline(
            BusinessRules.EvaluableRule rule, ClaimReport claim, Outcome outcome) {
        if (rule == null || rule.deadlineHours() == null
                || claim.policeReportAt() == null || claim.eventDate() == null) {
            return;
        }
        long hours = Duration.between(claim.eventDate(), claim.policeReportAt()).toHours();
        String evaluatedValue = "policeReportAt=+" + hours + "h max=" + rule.deadlineHours() + "h";
        if (hours < 0) {
            outcome.record(rule, false, evaluatedValue,
                    "La denuncia policial declarada es anterior al hecho — datos inconsistentes");
            return;
        }
        outcome.record(rule, hours <= rule.deadlineHours(), evaluatedValue,
                String.format("Denuncia policial fuera de plazo: %d hs desde el hecho, sobre un máximo de %d hs",
                        hours, rule.deadlineHours()));
    }

    private void evaluateReportDeadline(
            BusinessRules.EvaluableRule rule, ClaimReport claim, BusinessRules rules, Outcome outcome) {
        if (rule == null || rules.reportDeadlineHours() == null
                || claim.eventDate() == null || claim.reportedAt() == null) {
            return;
        }
        long hours = Duration.between(claim.eventDate(), claim.reportedAt()).toHours();
        String evaluatedValue = "reportedAt=+" + hours + "h max=" + rules.reportDeadlineHours() + "h";
        if (hours < 0) {
            outcome.record(rule, false, evaluatedValue,
                    "La denuncia declarada es anterior al hecho — datos inconsistentes");
            return;
        }
        outcome.record(rule, hours <= rules.reportDeadlineHours(), evaluatedValue,
                String.format("Denuncia fuera de plazo: %d hs desde el hecho, sobre el máximo de %d hs "
                                + "de la cobertura", hours, rules.reportDeadlineHours()));
    }

    /** Scoped by branch as the closest approximation to "per policy". */
    private void evaluateMaxAnnualEvents(
            BusinessRules.EvaluableRule rule, ClaimReport claim, InsuredHistory history,
            BusinessRules rules, Outcome outcome) {
        if (rule == null || rules.maxEventsPerYear() == null
                || claim.eventDate() == null || history.claims() == null) {
            return;
        }
        // Shared with the settlement so the quota and the payable percentage count the same events.
        int ordinal = history.eventOrdinalFor(claim.eventDate().toLocalDate(), claim.branch());
        int priorInWindow = ordinal - 1;
        outcome.record(rule, ordinal <= rules.maxEventsPerYear(),
                "events12m=" + ordinal + " max=" + rules.maxEventsPerYear(),
                String.format("Supera el tope de %s por año: %s en los últimos 12 meses",
                        count(rules.maxEventsPerYear(), "siniestro", "siniestros"),
                        count(priorInWindow, "siniestro previo", "siniestros previos")));
    }

    private static String count(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    /** Doesn't model which installments are unpaid: there is no installment ledger with period boundaries. */
    private void evaluatePolicyStanding(BusinessRules.EvaluableRule rule, InsuredPolicy policy, Outcome outcome) {
        if (rule == null) {
            return;
        }
        outcome.record(rule, policy.upToDate(), "upToDate=" + policy.upToDate(),
                "La póliza tiene saldo impago — cuántas cuotas se adeudan y si el hecho cae dentro de un "
                        + "período no abonado queda a criterio del analista");
    }

    /** {@code blocksFastTrack} follows each rule's own setting, so a rule can report without blocking. */
    private static final class Outcome {

        private final List<String> reasons = new ArrayList<>();
        private final List<RuleFinding> findings = new ArrayList<>();
        private boolean blocksFastTrack;

        void record(BusinessRules.EvaluableRule rule, boolean passed, String evaluatedValue, String failureReason) {
            findings.add(new RuleFinding(rule.id(), rule.ruleType(), passed, evaluatedValue));
            if (passed) {
                return;
            }
            reasons.add(failureReason);
            if (rule.blocksFastTrack()) {
                blocksFastTrack = true;
            }
        }
    }
}
