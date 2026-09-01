package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How far the coverage reaches: who it covers and whether it has anything left (D9). Two
 * {@code coverage} columns that were stored and nobody read.
 *
 * <ul>
 *   <li><b>{@code covers_family_group}</b> — if the coverage doesn't reach the family group and the
 *       injured party is a relative, the event isn't covered.</li>
 *   <li><b>{@code claim_exhausts_coverage}</b> — if a settled claim exhausts the coverage, the next
 *       one on the same policy has nothing left to answer with.</li>
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

    /**
     * @param reasons  readable reasons for the rules that failed, for the analyst
     * @param findings the auditable trace of both rules, passes included. They carry no
     *                 {@code ruleId}: these live on the coverage, not in {@code insurer_rule}.
     */
    public record Result(boolean blocksFastTrack, List<String> reasons, List<RuleFinding> findings) {

        public static Result none() {
            return new Result(false, List.of(), List.of());
        }
    }

    public Result evaluate(
            ClaimReport claim,
            InsuredHistory history,
            BusinessRules rules,
            Map<String, DocumentExtraction> documents) {

        List<String> reasons = new ArrayList<>();
        List<RuleFinding> findings = new ArrayList<>();

        evaluateFamilyGroup(rules, documents, reasons, findings);
        evaluateExhaustedCoverage(claim, history, rules, reasons, findings);

        boolean block = !reasons.isEmpty();
        if (block) {
            log.info("[CoverageScopeEvaluator] Alcance de cobertura incumplido (bloquea Fast Track): {}", reasons);
        }
        return new Result(block, reasons, findings);
    }

    /**
     * It only fires on an explicit {@code FAMILIAR}. {@code DESCONOCIDO} — or no document read —
     * leaves the rule unevaluated: the paper not saying whose device it was can't cost anyone
     * their coverage.
     */
    private void evaluateFamilyGroup(
            BusinessRules rules, Map<String, DocumentExtraction> documents, List<String> reasons,
            List<RuleFinding> findings) {
        if (!Boolean.FALSE.equals(rules.coversFamilyGroup())) {
            return; // the coverage reaches the family group, or isn't configured
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
        // A pass needs a document that actually said who it was. Nobody saying leaves the rule
        // UNEVALUATED — a third state that writes no row, same reason the rule doesn't fire on
        // DESCONOCIDO: silence about whose the item was can't count either way.
        DocumentExtraction.AffectedParty declared = documents.values().stream()
                .map(extraction -> extraction.fields().affectedParty())
                .filter(party -> party != null && party != DocumentExtraction.AffectedParty.DESCONOCIDO)
                .findFirst()
                .orElse(null);
        if (declared != null) {
            findings.add(finding(RuleType.COVERS_FAMILY_GROUP, true, "affectedParty=" + declared));
        }
    }

    /**
     * Counts only settled claims <b>on the same policy</b>: coverage is exhausted per policy, and
     * the same insured may have others. With no policy number in the history the rule doesn't take
     * part, rather than counting claims foreign to this coverage.
     */
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
        // Unlike the family group, this one always has an answer once the rule is on: the history
        // either has a settled claim on this policy or it doesn't. So the pass is written too —
        // "nothing consumed the coverage" is a verified fact, not an absence of data.
        findings.add(finding(RuleType.CLAIM_EXHAUSTS_COVERAGE, settled == 0,
                "settledClaimsOnPolicy=" + settled + " max=0"));
        if (settled > 0) {
            reasons.add("La cobertura ya fue consumida por un siniestro liquidado previo sobre esta "
                    + "póliza (un siniestro agota la cobertura)");
        }
    }

    /** No rule id: both rules are coverage columns, not rows of {@code insurer_rule}. */
    private static RuleFinding finding(RuleType type, boolean passed, String evaluatedValue) {
        return new RuleFinding(null, type.name(), passed, evaluatedValue);
    }
}
