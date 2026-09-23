package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How far the coverage reaches: family group, exhaustion by a settled claim, and the sum insured
 * consumed by accumulation (per coverage, not per policy). The model only extracts who was affected;
 * the rule is decided here. Like every hard rule, it blocks Fast Track and adds reasons, never closes
 * the case.
 */
@Service
public class CoverageScopeEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CoverageScopeEvaluator.class);

    private static final String SETTLED = "LIQUIDADO";

    public record Result(boolean blocksFastTrack, List<String> reasons, List<RuleFinding> findings) {

        public static Result none() {
            return new Result(false, List.of(), List.of());
        }
    }

    public Result evaluate(
            ClaimReport claim,
            InsuredPolicy policy,
            InsuredHistory history,
            BusinessRules rules,
            Map<String, DocumentExtraction> documents) {

        List<String> reasons = new ArrayList<>();
        List<RuleFinding> findings = new ArrayList<>();

        evaluateFamilyGroup(rules, documents, reasons, findings);
        evaluateExhaustedCoverage(claim, history, rules, reasons, findings);
        evaluateSumInsuredLimit(claim, policy, history, reasons);

        boolean block = !reasons.isEmpty();
        if (block) {
            log.info("[CoverageScopeEvaluator] Alcance de cobertura incumplido (bloquea Fast Track): {}", reasons);
        }
        return new Result(block, reasons, findings);
    }

    /** Only an explicit {@code FAMILIAR} fires it; unknown leaves it unevaluated. */
    private void evaluateFamilyGroup(
            BusinessRules rules, Map<String, DocumentExtraction> documents, List<String> reasons,
            List<RuleFinding> findings) {
        if (!Boolean.FALSE.equals(rules.coversFamilyGroup())) {
            return;
        }
        boolean affectedIsFamily = documents.values().stream()
                .map(extraction -> extraction.fields().affectedParty())
                .anyMatch(DocumentExtraction.AffectedParty.FAMILIAR::equals);
        if (affectedIsFamily) {
            findings.add(finding(RuleType.COVERS_FAMILY_GROUP, false, "affectedParty=FAMILIAR"));
            reasons.add("El damnificado es un familiar del asegurado y la cobertura no alcanza al "
                    + "grupo familiar conviviente");
            return;
        }
        // A PASS needs a document that said who it was; otherwise no row is written.
        DocumentExtraction.AffectedParty declared = documents.values().stream()
                .map(extraction -> extraction.fields().affectedParty())
                .filter(party -> party != null && party != DocumentExtraction.AffectedParty.DESCONOCIDO)
                .findFirst()
                .orElse(null);
        if (declared != null) {
            findings.add(finding(RuleType.COVERS_FAMILY_GROUP, true, "affectedParty=" + declared));
        }
    }

    /** Counts only settled claims on the same policy: the insured may have others. */
    private void evaluateExhaustedCoverage(
            ClaimReport claim, InsuredHistory history, BusinessRules rules, List<String> reasons,
            List<RuleFinding> findings) {
        if (!Boolean.TRUE.equals(rules.claimExhaustsCoverage())
                || history.claims() == null || claim.policyNumber() == null) {
            return;
        }
        long settled = history.claims().stream()
                .filter(record -> claim.policyNumber().equals(record.policyNumber()))
                .filter(record -> SETTLED.equalsIgnoreCase(record.status()))
                .count();
        // Always answerable once enabled, so a PASS is written too.
        findings.add(finding(RuleType.CLAIM_EXHAUSTS_COVERAGE, settled == 0,
                "settledClaimsOnPolicy=" + settled + " max=0"));
        if (settled > 0) {
            reasons.add("La cobertura ya fue consumida por un siniestro liquidado previo sobre esta "
                    + "póliza (un siniestro agota la cobertura)");
        }
    }

    /**
     * Accumulation per coverage: there is no policy-wide ceiling, so only claims settled against this
     * same coverage count. Prior claims with no recorded coverage are left out rather than guessed
     * (a wrong reason is worse than a missing one). Lifetime total: no per-renewal reset is modeled.
     */
    private void evaluateSumInsuredLimit(
            ClaimReport claim, InsuredPolicy policy, InsuredHistory history, List<String> reasons) {
        if (policy.insuredAmount() == null || policy.insuredAmount().signum() == 0
                || claim.claimedAmount() == null || history.claims() == null
                || claim.policyNumber() == null || claim.coverageName() == null) {
            return;
        }
        BigDecimal settledSoFar = history.claims().stream()
                .filter(record -> claim.policyNumber().equals(record.policyNumber()))
                .filter(record -> claim.coverageName().equalsIgnoreCase(record.coverageName()))
                .filter(record -> SETTLED.equalsIgnoreCase(record.status()))
                .map(record -> record.amountSettled() != null ? record.amountSettled() : record.amountClaimed())
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal projectedTotal = settledSoFar.add(claim.claimedAmount());
        if (projectedTotal.compareTo(policy.insuredAmount()) > 0) {
            reasons.add(String.format(
                    "La suma de siniestros liquidados previos sobre la cobertura '%s' (%s) más este "
                            + "reclamo (%s) supera su suma asegurada (%s)",
                    claim.coverageName(), settledSoFar, claim.claimedAmount(), policy.insuredAmount()));
        }
    }

    private static RuleFinding finding(RuleType type, boolean passed, String evaluatedValue) {
        return new RuleFinding(null, type.name(), passed, evaluatedValue);
    }
}
