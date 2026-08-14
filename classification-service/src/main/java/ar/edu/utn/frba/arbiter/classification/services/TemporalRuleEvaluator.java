package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Hard temporal/frequency rules, evaluated in code (not interpreted by the LLM). Today:
 * <ul>
 *   <li><b>D13</b> — policy validity: the event has to fall within {@code effectiveFrom..
 *       effectiveTo}.</li>
 *   <li><b>D9</b> — waiting period: the event can't fall within the {@code waiting_period_days}
 *       following the policy's start.</li>
 *   <li><b>D12</b> — deadline for the declared police report. <b>Provisional</b> threshold via
 *       property (see {@code policeReportDeadlineHours}), not configurable per insurer yet.</li>
 *   <li><b>D11</b> — reporting deadline: {@code reportedAt - occurredAt} can't exceed the
 *       coverage's {@code report_deadline_hours}.</li>
 *   <li><b>D10</b> — event cap per year: count the insured's claims in the branch within the last
 *       12 months; the current one can't exceed {@code max_events_per_year}.</li>
 * </ul>
 *
 * <p>These rules <b>block Fast Track</b> and contribute readable reasons for the analyst, instead
 * of shipping the text to the LLM for it to interpret. They don't close the case
 * (human-in-the-loop): they're findings, not a resolution. Each rule is only evaluated if it has
 * the data it needs; if any is missing, it doesn't take part (it doesn't block blindly).
 *
 * <p><b>Audit (rule_result):</b> unlike coverage exclusions, these limits are {@code coverage}
 * columns, not {@code insurer_rule} rows, and {@code rule_result.rule_id} is a NOT NULL FK to
 * {@code insurer_rule} — so for now they aren't audited in that table (they'd have to be modelled
 * as insurer rules, like the exclusions). See plan-reglas-evaluables.md §1.1.
 */
@Service
public class TemporalRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TemporalRuleEvaluator.class);

    /**
     * Deadline to file the police report, in hours from the event (D12).
     *
     * <p><b>Provisional, knowingly.</b> It should be configurable per insurer like the rest —
     * decision #12 of CLAUDE.md, and it's D4a's lesson — but today it has nowhere to live:
     * {@code coverage} has a single deadline column ({@code report_deadline_hours}) and D11 already
     * uses it for the deadline to report <b>to the insurer</b>, which is a different deadline.
     * Reusing it would make one number govern two distinct rules.
     *
     * <p>It stays a property rather than a constant so it can at least be changed per environment
     * without recompiling. The real fix is modelling it as an evaluable rule in
     * {@code insurer_rule} — like D3's coverage exclusion, which also brings {@code rule_result}
     * auditing for free — or adding a column to the DER. Pending enhancement, agreed with Fede on
     * 10/08.
     */
    private final long policeReportDeadlineHours;

    public TemporalRuleEvaluator(
            @Value("${arbiter.rules.police-report-deadline-hours:72}") long policeReportDeadlineHours) {
        this.policeReportDeadlineHours = policeReportDeadlineHours;
    }

    /**
     * @param blocksFastTrack {@code true} if any temporal rule failed (must not fast-track).
     * @param reasons         readable reasons for the rules that failed, for the analyst.
     */
    public record Result(boolean blocksFastTrack, List<String> reasons) {}

    public Result evaluate(ClaimReport claim, InsuredPolicy policy, InsuredHistory history, BusinessRules rules) {
        List<String> reasons = new ArrayList<>();

        evaluatePolicyInForce(claim, policy, reasons);
        evaluateWaitingPeriod(claim, policy, rules, reasons);
        evaluateReportDeadline(claim, rules, reasons);
        evaluatePoliceReportDeadline(claim, reasons);
        evaluateMaxAnnualEvents(claim, history, rules, reasons);

        boolean block = !reasons.isEmpty();
        if (block) {
            log.info("[TemporalRuleEvaluator] Reglas temporales incumplidas (bloquean Fast Track): {}", reasons);
        }
        return new Result(block, reasons);
    }

    /** D13 · the event has to fall within the policy's validity. */
    private void evaluatePolicyInForce(ClaimReport claim, InsuredPolicy policy, List<String> reasons) {
        if (claim.eventDate() == null || policy.effectiveFrom() == null || policy.effectiveTo() == null) {
            return;
        }
        LocalDate eventDate = claim.eventDate().toLocalDate();
        if (!policy.inForceOn(eventDate)) {
            reasons.add(String.format(
                    "El siniestro (%s) ocurrió fuera de la vigencia de la póliza (%s a %s)",
                    eventDate, policy.effectiveFrom(), policy.effectiveTo()));
        }
    }

    /**
     * D9 · waiting period: the coverage doesn't apply during the first
     * {@code waiting_period_days} from the policy's start, even if the policy is in force. It
     * exists so insurance isn't bought for an event that already happened or is imminent.
     *
     * <p>Different from validity (D13) even though both look at the same dates: validity says
     * whether there was a contract, the waiting period says whether that contract already provided
     * cover. And different from Fast Track's {@code minPolicyAgeMonths}, which decides the
     * <b>path</b> (a very new policy doesn't go the express way) and not the <b>entitlement</b>:
     * here the claim simply isn't covered.
     */
    private void evaluateWaitingPeriod(
            ClaimReport claim, InsuredPolicy policy, BusinessRules rules, List<String> reasons) {
        if (rules.waitingPeriodDays() == null || claim.eventDate() == null || policy.effectiveFrom() == null) {
            return;
        }
        LocalDate eventDate = claim.eventDate().toLocalDate();
        LocalDate coverageStart = policy.effectiveFrom().plusDays(rules.waitingPeriodDays());
        if (eventDate.isBefore(coverageStart)) {
            reasons.add(String.format(
                    "El siniestro (%s) ocurrió dentro de la carencia de %d días: la cobertura recién "
                            + "aplica desde el %s (póliza dada de alta el %s)",
                    eventDate, rules.waitingPeriodDays(), coverageStart, policy.effectiveFrom()));
        }
    }

    /**
     * D12 · the police report can't exceed the deadline from the event.
     *
     * <p>It's evaluated on what the insured <b>declared</b>. The certificate saying another date is
     * a different signal, crossed by {@code DocumentInconsistencyEvaluator}: filing late is one
     * thing, declaring a date the paper doesn't back is another.
     *
     * <p>Without {@code policeReportAt} the rule doesn't take part: "there was no police report" is
     * a legitimate case (not every claim cause involves one) and distinct from "there was one but
     * past the deadline".
     */
    private void evaluatePoliceReportDeadline(ClaimReport claim, List<String> reasons) {
        if (claim.policeReportAt() == null || claim.eventDate() == null) {
            return;
        }
        long hours = Duration.between(claim.eventDate(), claim.policeReportAt()).toHours();
        if (hours < 0) {
            reasons.add("La denuncia policial declarada es anterior a la fecha del hecho — dato inconsistente");
        } else if (hours > policeReportDeadlineHours) {
            reasons.add(String.format(
                    "Denuncia policial fuera de plazo: %d hs desde el hecho, supera el máximo de %d hs",
                    hours, policeReportDeadlineHours));
        }
    }

    /** D11 · la denuncia a la aseguradora no puede superar el plazo de la cobertura. */
    private void evaluateReportDeadline(ClaimReport claim, BusinessRules rules, List<String> reasons) {
        if (rules.reportDeadlineHours() == null || claim.eventDate() == null || claim.reportedAt() == null) {
            return;
        }
        long hours = Duration.between(claim.eventDate(), claim.reportedAt()).toHours();
        if (hours < 0) {
            reasons.add("La denuncia es anterior a la fecha del hecho declarada — dato inconsistente");
        } else if (hours > rules.reportDeadlineHours()) {
            reasons.add(String.format(
                    "Denuncia fuera de plazo: %d hs desde el hecho, supera el máximo de %d hs de la cobertura",
                    hours, rules.reportDeadlineHours()));
        }
    }

    /**
     * D10 · event cap per year. Counts the insured's prior claims in the same branch within the 12
     * months before the event; the current one can't exceed the cap. The history is per insured (it
     * carries no policy number), so it's narrowed by branch as the closest approximation to
     * "per policy".
     */
    private void evaluateMaxAnnualEvents(ClaimReport claim, InsuredHistory history, BusinessRules rules, List<String> reasons) {
        if (rules.maxEventsPerYear() == null || claim.eventDate() == null || history.claims() == null) {
            return;
        }
        LocalDate eventDate = claim.eventDate().toLocalDate();
        LocalDate windowStart = eventDate.minusYears(1);
        long priorInWindow = history.claims().stream()
                .filter(record -> record.date() != null)
                .filter(record -> claim.branch() == null || claim.branch().equalsIgnoreCase(record.branch()))
                .filter(record -> !record.date().isBefore(windowStart) && !record.date().isAfter(eventDate))
                .count();
        // El siniestro actual todavía no está en el historial: sería el (priorInWindow + 1)-ésimo.
        if (priorInWindow + 1 > rules.maxEventsPerYear()) {
            reasons.add(String.format(
                    "Supera el tope de %d evento(s) por año: %d siniestro(s) previo(s) en los últimos 12 meses",
                    rules.maxEventsPerYear(), priorInWindow));
        }
    }
}
