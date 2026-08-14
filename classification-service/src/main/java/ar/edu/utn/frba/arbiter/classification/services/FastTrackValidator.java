package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic Fast Track validation: runs before invoking the LLM and evaluates
 * the {@link BusinessRules.FastTrackThresholds} thresholds against the claim, policy,
 * and insured history data. No AI involved — these are plain evaluable rules.
 */
@Service
public class FastTrackValidator {

    private static final Logger log = LoggerFactory.getLogger(FastTrackValidator.class);

    public record Result(boolean fastTrack, List<String> reasons) {}

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
                    List.of("No hay criterios de Fast Track configurados para " + rules.branchId() + "/" + rules.claimCauseId()));
        }

        // priorClaimsWindowMonths no cuenta como criterio activo: no decide por sí solo, solo acota
        // maxPriorClaims' window. With that limit unconfigured, it evaluates nothing.
        if (thresholds.maxClaimedAmountRatio() == null
                && thresholds.maxPriorClaims() == null
                && thresholds.minPolicyAgeMonths() == null
                && thresholds.requiresUpToDatePolicy() == null
                && (thresholds.requiredDocumentTypes() == null || thresholds.requiredDocumentTypes().isEmpty())) {
            return new Result(false,
                    List.of("Fast Track configurado pero sin criterios activos para " + rules.branchId() + "/" + rules.claimCauseId()));
        }

        List<String> reasons = new ArrayList<>();
        boolean eligible = true;

        if (thresholds.maxClaimedAmountRatio() != null) {
            if (claim.claimedAmount() == null || policy.insuredAmount() == null || policy.insuredAmount().signum() == 0) {
                eligible = false;
                reasons.add("No se pudo evaluar el monto reclamado contra la suma asegurada");
            } else {
                double ratio = claim.claimedAmount().doubleValue() / policy.insuredAmount().doubleValue();
                if (ratio <= thresholds.maxClaimedAmountRatio()) {
                    reasons.add(String.format(
                            "Monto reclamado (%.1f%% de la suma asegurada) dentro del límite de Fast Track (%.1f%%)",
                            ratio * 100, thresholds.maxClaimedAmountRatio() * 100));
                } else {
                    eligible = false;
                    reasons.add(String.format(
                            "Monto reclamado (%.1f%% de la suma asegurada) supera el límite de Fast Track (%.1f%%)",
                            ratio * 100, thresholds.maxClaimedAmountRatio() * 100));
                }
            }
        }

        if (thresholds.maxPriorClaims() != null) {
            int priorClaims = priorClaimsInWindow(claim, history, thresholds.priorClaimsWindowMonths());
            String window = thresholds.priorClaimsWindowMonths() == null
                    ? ""
                    : " en los últimos " + thresholds.priorClaimsWindowMonths() + " meses";
            if (priorClaims <= thresholds.maxPriorClaims()) {
                reasons.add("Claims previos (" + priorClaims + ")" + window
                        + " dentro del límite de Fast Track (" + thresholds.maxPriorClaims() + ")");
            } else {
                eligible = false;
                reasons.add("Claims previos (" + priorClaims + ")" + window
                        + " supera el límite de Fast Track (" + thresholds.maxPriorClaims() + ")");
            }
        }

        if (thresholds.minPolicyAgeMonths() != null) {
            Long ageMonths = policyAgeMonths(claim, policy);
            if (ageMonths == null) {
                // Without the policy's start date or the event's date the age can't be asserted,
                // and Fast Track only proceeds on what's verifiable.
                eligible = false;
                reasons.add("No se pudo determinar la antigüedad de la póliza — no aplica Fast Track");
            } else if (ageMonths >= thresholds.minPolicyAgeMonths()) {
                reasons.add("Antigüedad de la póliza (" + ageMonths + " meses) cumple el mínimo de Fast Track ("
                        + thresholds.minPolicyAgeMonths() + ")");
            } else {
                eligible = false;
                reasons.add("Antigüedad de la póliza (" + ageMonths + " meses) por debajo del mínimo de Fast Track ("
                        + thresholds.minPolicyAgeMonths() + ")");
            }
        }

        if (Boolean.TRUE.equals(thresholds.requiresUpToDatePolicy())) {
            if (policy.upToDate()) {
                reasons.add("Póliza al día con sus pagos");
            } else {
                eligible = false;
                reasons.add("Póliza con pagos atrasados — no aplica Fast Track");
            }
        }

        if (thresholds.requiredDocumentTypes() != null && !thresholds.requiredDocumentTypes().isEmpty()) {
            if (documentTexts == null) {
                reasons.add("Documentación ya verificada previamente — no se re-evalúa en Fast Track");
            } else {
                List<String> missing = thresholds.requiredDocumentTypes().stream()
                        .filter(type -> documentTexts.get(type) == null || documentTexts.get(type).isBlank())
                        .toList();
                if (missing.isEmpty()) {
                    reasons.add("Documentación requerida para Fast Track presente: " + thresholds.requiredDocumentTypes());
                } else {
                    eligible = false;
                    reasons.add("Falta documentación requerida para Fast Track: " + missing);
                }
            }
        }

        log.info("[FastTrackValidator] policy='{}' eligible={} reasons={}", policy.policyNumber(), eligible, reasons);
        return new Result(eligible, reasons);
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

    /** @return months between the policy's start and the event, or null if either date is missing. */
    private Long policyAgeMonths(ClaimReport claim, InsuredPolicy policy) {
        if (claim.eventDate() == null || policy.effectiveFrom() == null) {
            return null;
        }
        return ChronoUnit.MONTHS.between(policy.effectiveFrom(), claim.eventDate().toLocalDate());
    }
}
