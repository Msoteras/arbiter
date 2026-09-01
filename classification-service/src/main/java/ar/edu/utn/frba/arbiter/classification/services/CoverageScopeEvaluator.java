package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How far the coverage reaches: who it covers and whether it has anything left (D9). Two
 * {@code coverage} columns that were stored and nobody read.
 *
 * <ul>
 *   <li><b>{@code covers_family_group}</b> — if the coverage doesn't reach the family group and the
 *       injured party is a relative, the event isn't covered.</li>
 *   <li><b>{@code claim_exhausts_coverage}</b> — if a settled claim exhausts the coverage, the next
 *       one on the same policy has nothing left to answer with.</li>
 *   <li><b>suma asegurada</b> — a coverage that does <i>not</i> exhaust on a single settled claim
 *       can still run out through accumulation: prior settled amounts plus this claim can't exceed
 *       {@code policy.insuredAmount()} (doc de dominio BBVA §6.4). Not a {@code coverage} column —
 *       the limit is a fact the insurer DB already carries on the policy, so there's nothing for the
 *       referente to configure.</li>
 * </ul>
 *
 * <p><b>Why the LLM doesn't decide the first one.</b> Knowing whose device it was requires reading
 * the narrative, and reading is the one thing code can't do — but interpreting the rule it can. So
 * it's split in two, same as D4a: the extraction pass returns a <b>typed fact</b>
 * ({@link DocumentExtraction.AffectedParty}) and the rule is evaluated here. The model never
 * decides whether there's coverage; it only supplies the data.
 *
 * <p><b>Source of the data: the coverage, not the policy.</b> {@code coverage.covers_family_group}
 * (what the referente configures) and {@code poliza.cubre_grupo_familiar} (insurer DB) both exist
 * and already contradict each other in the seed. The referente's wins (Fede's call, 10/08).
 *
 * <p>Like the rest of the hard rules: they <b>block Fast Track and contribute reasons</b>, they
 * don't close the case. An exclusion doesn't reject the settlement on its own — the analyst signs
 * (CLAUDE.md #5).
 */
@Service
public class CoverageScopeEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CoverageScopeEvaluator.class);

    /** Resolution status meaning the prior claim actually consumed the coverage. */
    private static final String SETTLED = "LIQUIDADO";

    /** @param reasons readable reasons for the rules that failed, for the analyst. */
    public record Result(boolean blocksFastTrack, List<String> reasons) {}

    public Result evaluate(
            ClaimReport claim,
            InsuredPolicy policy,
            InsuredHistory history,
            BusinessRules rules,
            Map<String, DocumentExtraction> documents) {

        List<String> reasons = new ArrayList<>();

        evaluateFamilyGroup(rules, documents, reasons);
        evaluateExhaustedCoverage(claim, history, rules, reasons);
        evaluateSumInsuredLimit(claim, policy, history, reasons);

        boolean block = !reasons.isEmpty();
        if (block) {
            log.info("[CoverageScopeEvaluator] Alcance de cobertura incumplido (bloquea Fast Track): {}", reasons);
        }
        return new Result(block, reasons);
    }

    /**
     * It only fires on an explicit {@code FAMILIAR}. {@code DESCONOCIDO} — or no document read —
     * leaves the rule unevaluated: the paper not saying whose device it was can't cost anyone
     * their coverage.
     */
    private void evaluateFamilyGroup(
            BusinessRules rules, Map<String, DocumentExtraction> documents, List<String> reasons) {
        if (!Boolean.FALSE.equals(rules.coversFamilyGroup())) {
            return; // the coverage reaches the family group, or isn't configured
        }
        boolean affectedIsFamily = documents.values().stream()
                .map(extraction -> extraction.fields().affectedParty())
                .anyMatch(DocumentExtraction.AffectedParty.FAMILIAR::equals);
        if (affectedIsFamily) {
            reasons.add("El damnificado es un familiar del asegurado y la cobertura no alcanza al "
                    + "grupo familiar conviviente");
        }
    }

    /**
     * Counts only settled claims <b>on the same policy</b>: coverage is exhausted per policy, and
     * the same insured may have others. With no policy number in the history the rule doesn't take
     * part, rather than counting claims foreign to this coverage.
     */
    private void evaluateExhaustedCoverage(
            ClaimReport claim, InsuredHistory history, BusinessRules rules, List<String> reasons) {
        if (!Boolean.TRUE.equals(rules.claimExhaustsCoverage())
                || history.claims() == null || claim.policyNumber() == null) {
            return;
        }
        boolean alreadySettled = history.claims().stream()
                .filter(record -> claim.policyNumber().equals(record.policyNumber()))
                .anyMatch(record -> SETTLED.equalsIgnoreCase(record.status()));
        if (alreadySettled) {
            reasons.add("La cobertura ya fue consumida por un siniestro liquidado previo sobre esta "
                    + "póliza (un siniestro agota la cobertura)");
        }
    }

    /**
     * Complements {@link #evaluateExhaustedCoverage}: a coverage that doesn't exhaust on any single
     * settled claim can still run out by accumulation. Sums every settled claim on the same policy
     * plus this one and compares against {@code policy.insuredAmount()} — no period boundary
     * modeled (same limitation as {@code POLICY_STANDING}'s arrears tiers: installment/policy-year
     * boundaries aren't in any schema today), so this is a lifetime total against what the history
     * returned, not a per-renewal reset.
     *
     * <p>Missing {@code insuredAmount} or {@code claimedAmount} means the rule doesn't participate —
     * same criterion {@link FastTrackValidator} uses for its amount ratio.
     */
    private void evaluateSumInsuredLimit(
            ClaimReport claim, InsuredPolicy policy, InsuredHistory history, List<String> reasons) {
        if (policy.insuredAmount() == null || policy.insuredAmount().signum() == 0
                || claim.claimedAmount() == null || history.claims() == null || claim.policyNumber() == null) {
            return;
        }
        BigDecimal settledSoFar = history.claims().stream()
                .filter(record -> claim.policyNumber().equals(record.policyNumber()))
                .filter(record -> SETTLED.equalsIgnoreCase(record.status()))
                .map(record -> record.amountSettled() != null ? record.amountSettled() : record.amountClaimed())
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal projectedTotal = settledSoFar.add(claim.claimedAmount());
        if (projectedTotal.compareTo(policy.insuredAmount()) > 0) {
            reasons.add(String.format(
                    "La suma de siniestros liquidados previos (%s) más este reclamo (%s) supera la suma "
                            + "asegurada de la póliza (%s)",
                    settledSoFar, claim.claimedAmount(), policy.insuredAmount()));
        }
    }
}
