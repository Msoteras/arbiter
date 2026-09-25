package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
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
 * Deterministic Fast Track gate, evaluated before the LLM. Every compared criterion leaves a
 * {@link RuleFinding}, passes included: a Fast Track writes no {@code llm_analysis} row, so this is
 * how the analyst sees what the gate checked.
 */
@Service
public class FastTrackValidator {

    private static final Logger log = LoggerFactory.getLogger(FastTrackValidator.class);

    /** {@code rule_result.evaluated_value} is {@code VARCHAR(150)}. */
    private static final int EVALUATED_VALUE_MAX = 150;

    /** @param findings empty when nothing was compared (no Fast Track or no active criterion) */
    public record Result(boolean fastTrack, List<String> reasons, List<RuleFinding> findings) {}

    /**
     * @param documents extractions by document type; null when they were already verified. Their
     *                  content is the LLM's job: the gate checks each required one is present and
     *                  was actually read. An unreadable one ({@code FAILED}) counts as missing — a
     *                  Fast Track can't rest on paperwork nobody read. A {@code PARTIAL} one still
     *                  passes, but says so: its data never reached the consistency rules.
     */
    public Result evaluate(
            ClaimReport claim,
            InsuredPolicy policy,
            InsuredHistory history,
            BusinessRules rules,
            Map<String, DocumentExtraction> documents
    ) {
        BusinessRules.FastTrackThresholds thresholds = rules.fastTrackThresholds();
        if (thresholds == null) {
            return new Result(false,
                    List.of("No hay criterios de Fast Track configurados para " + rules.branchId() + "/" + rules.claimCauseId()),
                    List.of());
        }

        // priorClaimsWindowMonths isn't a criterion on its own: it only scopes maxPriorClaims.
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
            // Locale.ROOT: the audited value must read the same wherever the JVM runs.
            String max = String.format(Locale.ROOT, "%.1f%%", thresholds.maxClaimedAmountRatio() * 100);
            if (claim.claimedAmount() == null || policy.insuredAmount() == null || policy.insuredAmount().signum() == 0) {
                eligible = false;
                reasons.add("No se pudo evaluar el monto reclamado contra la suma asegurada");
                // Unlike coverage-scope rules, missing data here FAILS: it costs the fast lane, and
                // the row explains why.
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
                // Fast Track only proceeds on what's verifiable.
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
            if (documents == null) {
                // No documents to look at: no row, since a PASS would claim unseen paperwork was verified.
                reasons.add("Documentación ya verificada previamente — no se re-evalúa en Fast Track");
            } else {
                List<String> missing = thresholds.requiredDocumentTypes().stream()
                        .filter(type -> documents.get(type) == null || documents.get(type).transcription().isBlank())
                        .toList();
                List<String> unreadable = withStatus(thresholds.requiredDocumentTypes(), documents,
                        DocumentExtraction.Status.FAILED);
                List<String> partial = withStatus(thresholds.requiredDocumentTypes(), documents,
                        DocumentExtraction.Status.PARTIAL);
                boolean passed = missing.isEmpty() && unreadable.isEmpty();
                eligible &= passed;
                if (!missing.isEmpty()) {
                    reasons.add("Falta documentación requerida para Fast Track: " + missing);
                }
                if (!unreadable.isEmpty()) {
                    reasons.add("No se pudo leer documentación requerida para Fast Track: " + unreadable
                            + " — no aplica Fast Track");
                }
                if (passed) {
                    reasons.add("Documentación requerida para Fast Track presente: " + thresholds.requiredDocumentTypes());
                }
                // Not a block: the fast lane stays, the analyst is told what went unchecked.
                if (!partial.isEmpty()) {
                    reasons.add("Lectura incompleta de " + partial + ": sus datos no se pudieron comparar con"
                            + " lo declarado — se sugiere revisión manual del analista");
                }
                findings.add(finding(RuleType.FT_REQUIRED_DOCS, passed,
                        "required=" + required
                                + " missing=" + (missing.isEmpty() ? "ninguno" : String.join(",", missing))
                                + (unreadable.isEmpty() ? "" : " unreadable=" + String.join(",", unreadable))
                                + (partial.isEmpty() ? "" : " partial=" + String.join(",", partial))));
            }
        }

        log.info("[FastTrackValidator] policy='{}' eligible={} reasons={}", policy.policyNumber(), eligible, reasons);
        return new Result(eligible, reasons, findings);
    }

    private static List<String> withStatus(List<String> types, Map<String, DocumentExtraction> documents,
                                           DocumentExtraction.Status status) {
        return types.stream()
                .filter(type -> documents.get(type) != null && documents.get(type).status() == status)
                .toList();
    }

    /** Truncated to the column width: an audit row that fails to insert audits nothing. */
    private static RuleFinding finding(RuleType type, boolean passed, String evaluatedValue) {
        String value = evaluatedValue.length() <= EVALUATED_VALUE_MAX
                ? evaluatedValue
                : evaluatedValue.substring(0, EVALUATED_VALUE_MAX - 1) + "…";
        return new RuleFinding(null, type.name(), passed, value);
    }

    /** Without a window, the whole history counts. The window runs back from the event, not from today. */
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

    /** Null if either date is missing. */
    private Long policyAgeMonths(ClaimReport claim, InsuredPolicy policy) {
        if (claim.eventDate() == null || policy.effectiveFrom() == null) {
            return null;
        }
        return ChronoUnit.MONTHS.between(policy.effectiveFrom().toLocalDate(), claim.eventDate().toLocalDate());
    }
}
