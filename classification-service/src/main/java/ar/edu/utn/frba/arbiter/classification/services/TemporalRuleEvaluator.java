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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Hard temporal/frequency rules, evaluated by code (never interpreted by the LLM). Today:
 * <ul>
 *   <li><b>D13</b> {@code POLICY_IN_FORCE} — the event has to fall within the policy's coverage
 *       window ({@code effectiveFrom..effectiveTo}).</li>
 *   <li><b>D9</b> {@code WAITING_PERIOD} — the event can't fall within the
 *       {@code waiting_period_days} following the policy's start date.</li>
 *   <li><b>D11</b> {@code REPORT_DEADLINE} — {@code reportedAt - occurredAt} can't exceed the
 *       coverage's {@code report_deadline_hours}.</li>
 *   <li><b>D12</b> {@code POLICE_DEADLINE} — deadline for the declared police report, counted
 *       from the event.</li>
 *   <li><b>D10</b> {@code MAX_EVENTS_YEAR} — cap on the insured's claims in the branch over the
 *       trailing 12 months.</li>
 *   <li><b>Arrears</b> {@code POLICY_STANDING} — the policy isn't up to date with its payments.</li>
 * </ul>
 *
 * <p><b>Each rule is evaluated only if the insurer has it active</b> — i.e. only if there's an
 * {@code insurer_rule} row of that type for the coverage (served by rules-service through
 * {@code rules.evaluableRules()}, which the referente turns on and off from the panel). No row,
 * no evaluation. That's what makes these thresholds adjustable without a redeploy (decision #12),
 * and in particular is what took the police-report deadline out of the fixed 72h property that
 * used to apply to every insurer.
 *
 * <p><b>The threshold and the switch live in different places, on purpose.</b> The waiting period,
 * the report deadline and the events cap are terms of the contract and stay as {@code coverage}
 * columns; the {@code insurer_rule} row only says whether the rule runs. The exceptions are
 * {@code POLICE_DEADLINE}, whose threshold travels on the rule itself ({@code deadlineHours})
 * because it has no column of its own — the coverage carries a single deadline and D11 already
 * uses it, and that's a different deadline — and {@code POLICY_STANDING}, which needs no threshold
 * at all: its source fact ({@code policy.upToDate()}) is already a boolean.
 *
 * <p>Besides being switched on, each rule needs <b>the data</b> to evaluate itself; missing data
 * means the rule doesn't participate (it never blocks blindly). "No police report was filed", for
 * instance, is a legitimate case and different from "filed, but past the deadline".
 *
 * <p>These rules <b>block Fast Track</b> and contribute readable reasons for the analyst, instead
 * of sending the raw text to the LLM to interpret. They don't close the case (human-in-the-loop,
 * CLAUDE.md #5): they're findings, not a resolution.
 *
 * <p><b>Audit trail:</b> every rule that gets evaluated leaves a {@link RuleFinding} — PASS and
 * FAIL — that {@code ClassificationResultsService} writes to {@code rule_result}, pointing at the
 * {@code insurer_rule} that was evaluated. Before this there was nothing to point at
 * ({@code rule_result.rule_id} is a NOT NULL FK), and Disposición SSN 2/2023's audit trail only
 * covered coverage exclusions.
 */
@Service
public class TemporalRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TemporalRuleEvaluator.class);

    /** Para los `evaluated_value`/mensajes: la vigencia lleva hora, y hay que mostrarla. */
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /**
     * @param blocksFastTrack {@code true} if any temporal rule failed (must not fast-track).
     * @param reasons         readable reasons from the rules that failed, for the analyst.
     * @param findings        one row per rule evaluated (PASS/FAIL), to audit in {@code rule_result}.
     */
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

    /**
     * The hard temporal rules the insurer has active, indexed by type. rules-service already
     * filters out the inactive ones; an unknown type is discarded here instead of sinking the
     * classification (the engine can be older than the panel's rule catalog).
     */
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

    /**
     * D13 · the event has to fall within the policy's coverage window.
     *
     * <p>Compared as a full timestamp, not truncated to the day: the reference policy fixes
     * vigencia with an exact hour ("desde las 12:00 hs del..."), and comparing only by date gave
     * false accepts at the edge — an event two hours before coverage started, same calendar day,
     * used to pass ({@code policy.effectiveFrom()/effectiveTo()} already carry the hour).
     */
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
     * D9 · waiting period: the coverage doesn't apply during the first {@code waiting_period_days}
     * since the policy's start date, even if the policy is in force. Exists so a policy can't be
     * bought against an event that already happened or is imminent.
     *
     * <p>Different from being in force (D13) even though both look at the same dates: being in
     * force says there was a contract, the waiting period says whether that contract already gave
     * coverage. And different from Fast Track's {@code minPolicyAgeMonths}, which decides the
     * <b>path</b> (a very new policy doesn't go the expedited route) and not the <b>right</b> to
     * coverage: here the claim simply isn't covered.
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
     * D12 · the police report can't come in past the deadline counted from the event. The
     * threshold travels on the rule itself because it has no {@code coverage} column — see the
     * class javadoc.
     *
     * <p>Evaluated against what the insured <b>declared</b>. If the actual document states a
     * different date that's a separate signal, cross-checked by {@code DocumentInconsistencyEvaluator}:
     * arriving late is one thing, declaring a date the paper doesn't back up is another.
     *
     * <p>Without {@code policeReportAt} the rule doesn't participate: "no police report was filed"
     * is legitimate (not every hecho generador requires one) and different from "filed, but past
     * the deadline".
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

    /** D11 · the report to the insurer can't come in past the coverage's deadline. */
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

    /**
     * D10 · cap on claims per year. Counts the insured's prior claims in the same branch over the
     * 12 months before the event; the current one can't push past the cap. The history is
     * per-insured (it carries no policy number), so it's scoped by branch as the closest
     * approximation to "per policy".
     */
    private void evaluateMaxAnnualEvents(
            BusinessRules.EvaluableRule rule, ClaimReport claim, InsuredHistory history,
            BusinessRules rules, Outcome outcome) {
        if (rule == null || rules.maxEventsPerYear() == null
                || claim.eventDate() == null || history.claims() == null) {
            return;
        }
        LocalDate eventDate = claim.eventDate().toLocalDate();
        LocalDate windowStart = eventDate.minusYears(1);
        long priorInWindow = history.claims().stream()
                .filter(record -> record.date() != null)
                .filter(record -> claim.branch() == null || claim.branch().equalsIgnoreCase(record.branch()))
                .filter(record -> !record.date().isBefore(windowStart) && !record.date().isAfter(eventDate))
                .count();
        // The current claim isn't in the history yet: it would be the (priorInWindow + 1)-th.
        outcome.record(rule, priorInWindow + 1 <= rules.maxEventsPerYear(),
                "events12m=" + (priorInWindow + 1) + " max=" + rules.maxEventsPerYear(),
                String.format("Supera el tope de %d siniestro(s) por año: %d siniestro(s) previo(s) "
                                + "en los últimos 12 meses", rules.maxEventsPerYear(), priorInWindow));
    }

    /**
     * Arrears: the policy isn't up to date with its payments. No threshold to evaluate — the
     * source fact ({@code policy.upToDate()}) is already the answer.
     *
     * <p>Doesn't model how many installments are unpaid or whether the event falls inside a paid
     * or unpaid billing period — that needs an installment ledger with period boundaries this
     * platform doesn't have (see {@link RuleType#POLICY_STANDING}'s javadoc). What this rule adds
     * over the pre-existing signals (the Fast Track's optional {@code requiresUpToDatePolicy} gate
     * and the {@code policy_standing} scoring factor) is that it's now audited and, when active,
     * mandatory — a policy in arrears can no longer sail through Fast Track just because the
     * referente forgot to check a box, and every run leaves a trace either way.
     */
    private void evaluatePolicyStanding(BusinessRules.EvaluableRule rule, InsuredPolicy policy, Outcome outcome) {
        if (rule == null) {
            return;
        }
        outcome.record(rule, policy.upToDate(), "upToDate=" + policy.upToDate(),
                "La póliza tiene saldo impago — cuántas cuotas se adeudan y si el hecho cae dentro de un "
                        + "período no abonado queda a criterio del analista");
    }

    /**
     * What accumulates while the rules run: the auditable trail of every one of them, and the
     * reasons behind the ones that failed. {@code blocksFastTrack} comes from each rule's own
     * {@code blocks_fast_track}, not from "something failed" — it's the column the referente
     * configures, and honoring it is what lets a rule report without stopping the expedited path.
     */
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
