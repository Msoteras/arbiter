package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic Fast Track validation: runs before invoking the LLM and evaluates
 * the {@link BusinessRules.FastTrackThresholds} thresholds against the claim, policy,
 * and insured history data. No AI involved — these are plain evaluable rules.
 *
 * <p>Every criterion it compares leaves a {@link RuleFinding}, passes included, so the analyst
 * sees what the gate checked instead of a bare "Fast Track" label. Until H0038 this was computed,
 * logged and dropped: the reasons reached {@code ClassificationResponse.factors} and died in
 * {@code ClassificationResultsService}, which writes no {@code llm_analysis} row for a
 * deterministic outcome.
 */
@Service
public class FastTrackValidator {

    private static final Logger log = LoggerFactory.getLogger(FastTrackValidator.class);

    /** {@code rule_result.evaluated_value} is {@code VARCHAR(150)}. */
    private static final int EVALUATED_VALUE_MAX = 150;

    /**
     * @param findings one row per criterion actually compared, for {@code rule_result}. Empty when
     *                 the gate didn't get to compare anything (no Fast Track configured, or
     *                 configured with no active criterion): there's nothing to audit, and an empty
     *                 table is honest about that
     */
    public record Result(boolean fastTrack, List<String> reasons, List<RuleFinding> findings) {}

    /**
     * @param documentTexts OCR text of the already-attached documents, indexed by type
     *                       (e.g. "police_report" -> text). Only the PRESENCE of the
     *                       required document is evaluated, not its content — reading and
     *                       interpreting what the document says is the LLM's job, not this gate's.
     */
    public Result evaluate(
            ClaimReport claim,
            InsuredPolicy policy,
            InsuredHistory history,
            BusinessRules rules,
            Map<String, String> documentTexts
    ) {
        BusinessRules.FastTrackThresholds thresholds = rules.fastTrackThresholds();
        if (thresholds == null) {
            return new Result(false,
                    List.of("No hay criterios de Fast Track configurados para " + rules.branchId() + "/" + rules.claimCauseId()),
                    List.of());
        }

        // priorClaimsWindowMonths no cuenta como criterio activo: no decide por sí solo, solo acota
        // maxPriorClaims' window. With that limit unconfigured, it evaluates nothing.
        if (thresholds.maxClaimedAmountRatio() == null
                && thresholds.maxPriorClaims() == null
                && thresholds.minPolicyAgeMonths() == null
                && thresholds.requiresUpToDatePolicy() == null
                && (thresholds.requiredDocumentTypes() == null || thresholds.requiredDocumentTypes().isEmpty())) {
            return new Result(false,
                    List.of("Fast Track configurado pero sin criterios activos para " + rules.branchId() + "/" + rules.claimCauseId()),
                    List.of());
        }

        List<String> reasons = new ArrayList<>();
        List<RuleFinding> findings = new ArrayList<>();
        boolean eligible = true;

        if (thresholds.maxClaimedAmountRatio() != null) {
            // Locale.ROOT: the audited value has to read the same wherever the JVM runs. The
            // reasons below are prose for a person and stay in the platform's locale.
            String max = String.format(Locale.ROOT, "%.1f%%", thresholds.maxClaimedAmountRatio() * 100);
            if (claim.claimedAmount() == null || policy.insuredAmount() == null || policy.insuredAmount().signum() == 0) {
                eligible = false;
                reasons.add("No se pudo evaluar el monto reclamado contra la suma asegurada");
                // A criterion that couldn't be checked FAILS rather than going unwritten: unlike the
                // coverage-scope rules, here missing data has a consequence (no fast lane), and the
                // row is what explains it.
                findings.add(finding(RuleType.FT_AMOUNT_RATIO, false, "ratio=sin datos max=" + max));
            } else {
                double ratio = claim.claimedAmount().doubleValue() / policy.insuredAmount().doubleValue();
                boolean within = ratio <= thresholds.maxClaimedAmountRatio();
                eligible &= within;
                reasons.add(String.format(
                        within
                                ? "Monto reclamado (%.1f%% de la suma asegurada) dentro del límite de Fast Track (%.1f%%)"
                                : "Monto reclamado (%.1f%% de la suma asegurada) supera el límite de Fast Track (%.1f%%)",
                        ratio * 100, thresholds.maxClaimedAmountRatio() * 100));
                findings.add(finding(RuleType.FT_AMOUNT_RATIO, within,
                        String.format(Locale.ROOT, "ratio=%.1f%% max=%s", ratio * 100, max)));
            }
        }

        if (thresholds.maxPriorClaims() != null) {
            int priorClaims = priorClaimsInWindow(claim, history, thresholds.priorClaimsWindowMonths());
            String window = thresholds.priorClaimsWindowMonths() == null
                    ? ""
                    : " en los últimos " + thresholds.priorClaimsWindowMonths() + " meses";
            boolean within = priorClaims <= thresholds.maxPriorClaims();
            eligible &= within;
            reasons.add("Claims previos (" + priorClaims + ")" + window
                    + (within ? " dentro del límite de Fast Track (" : " supera el límite de Fast Track (")
                    + thresholds.maxPriorClaims() + ")");
            findings.add(finding(RuleType.FT_PRIOR_CLAIMS, within,
                    "priorClaims=" + priorClaims + " max=" + thresholds.maxPriorClaims()
                            + (thresholds.priorClaimsWindowMonths() == null
                                    ? "" : " windowMonths=" + thresholds.priorClaimsWindowMonths())));
        }

        if (thresholds.minPolicyAgeMonths() != null) {
            Long ageMonths = policyAgeMonths(claim, policy);
            if (ageMonths == null) {
                // Without the policy's start date or the event's date the age can't be asserted,
                // and Fast Track only proceeds on what's verifiable.
                eligible = false;
                reasons.add("No se pudo determinar la antigüedad de la póliza — no aplica Fast Track");
                findings.add(finding(RuleType.FT_POLICY_AGE, false,
                        "policyAgeMonths=sin datos min=" + thresholds.minPolicyAgeMonths()));
            } else {
                boolean within = ageMonths >= thresholds.minPolicyAgeMonths();
                eligible &= within;
                reasons.add("Antigüedad de la póliza (" + ageMonths + " meses) "
                        + (within ? "cumple el mínimo de Fast Track (" : "por debajo del mínimo de Fast Track (")
                        + thresholds.minPolicyAgeMonths() + ")");
                findings.add(finding(RuleType.FT_POLICY_AGE, within,
                        "policyAgeMonths=" + ageMonths + " min=" + thresholds.minPolicyAgeMonths()));
            }
        }

        if (Boolean.TRUE.equals(thresholds.requiresUpToDatePolicy())) {
            eligible &= policy.upToDate();
            reasons.add(policy.upToDate()
                    ? "Póliza al día con sus pagos"
                    : "Póliza con pagos atrasados — no aplica Fast Track");
            findings.add(finding(RuleType.FT_POLICY_UP_TO_DATE, policy.upToDate(),
                    "upToDate=" + policy.upToDate()));
        }

        if (thresholds.requiredDocumentTypes() != null && !thresholds.requiredDocumentTypes().isEmpty()) {
            String required = String.join(",", thresholds.requiredDocumentTypes());
            if (documentTexts == null) {
                // Nobody handed us the documents to look at, so nothing was compared: no row, same
                // criterion CoverageScopeEvaluator uses for a rule that didn't get to evaluate.
                // Writing a PASS here would claim the gate verified paperwork it never saw.
                reasons.add("Documentación ya verificada previamente — no se re-evalúa en Fast Track");
            } else {
                List<String> missing = thresholds.requiredDocumentTypes().stream()
                        .filter(type -> documentTexts.get(type) == null || documentTexts.get(type).isBlank())
                        .toList();
                eligible &= missing.isEmpty();
                reasons.add(missing.isEmpty()
                        ? "Documentación requerida para Fast Track presente: " + thresholds.requiredDocumentTypes()
                        : "Falta documentación requerida para Fast Track: " + missing);
                findings.add(finding(RuleType.FT_REQUIRED_DOCS, missing.isEmpty(),
                        "required=" + required
                                + " missing=" + (missing.isEmpty() ? "ninguno" : String.join(",", missing))));
            }
        }

        log.info("[FastTrackValidator] policy='{}' eligible={} reasons={}", policy.policyNumber(), eligible, reasons);
        return new Result(eligible, reasons, findings);
    }

    /**
     * No rule id: the gate's thresholds aren't an {@code insurer_rule} row anyone can point at from
     * here (see {@link RuleType#FT_AMOUNT_RATIO}). Truncated to the column's width — an audit row
     * that fails to insert audits nothing.
     */
    private static RuleFinding finding(RuleType type, boolean passed, String evaluatedValue) {
        String value = evaluatedValue.length() <= EVALUATED_VALUE_MAX
                ? evaluatedValue
                : evaluatedValue.substring(0, EVALUATED_VALUE_MAX - 1) + "…";
        return new RuleFinding(null, type.name(), passed, value);
    }

    /**
     * Prior claims counting against the limit. With no window configured it uses the full
     * historical count, which is exactly what it did before the field existed: the limit was
     * compared against the insured's lifetime claims, so "at most 1 prior" locked a fifteen-year
     * customer with two old claims out of Fast Track forever (D14).
     *
     * <p>The window counts backwards from the <b>event</b> and not from today, like
     * {@code TemporalRuleEvaluator}'s annual cap: the criterion is the insured's situation when the
     * claim occurred, not when someone looks at the case.
     */
    private int priorClaimsInWindow(ClaimReport claim, InsuredHistory history, Integer windowMonths) {
        if (windowMonths == null || claim.eventDate() == null || history.claims() == null) {
            return history.previousClaimsCount();
        }
        LocalDate windowStart = claim.eventDate().toLocalDate().minusMonths(windowMonths);
        return (int) history.claims().stream()
                .filter(record -> record.date() != null)
                .filter(record -> !record.date().isBefore(windowStart))
                .count();
    }

    /**
     * @return months between the policy's start and the event, or null if either date is missing.
     *         Truncated to the day on both sides: policy age in months doesn't care about the hour
     *         the way vigencia itself does (D13).
     */
    private Long policyAgeMonths(ClaimReport claim, InsuredPolicy policy) {
        if (claim.eventDate() == null || policy.effectiveFrom() == null) {
            return null;
        }
        return ChronoUnit.MONTHS.between(policy.effectiveFrom().toLocalDate(), claim.eventDate().toLocalDate());
    }
}
