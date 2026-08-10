package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluación determinística de las reglas duras de cobertura — hoy, las <b>exclusiones</b>: qué
 * hechos generadores NO cubre la cobertura del claim. Corre <b>antes</b> del gate de Fast Track (una
 * exclusión dura hace irrelevante al Fast Track) y sin LLM: es código que compara ids, no
 * interpretación del modelo. Cierra el D3 del handoff (nada validaba que el hecho generador
 * estuviera cubierto) y, junto con la escritura de {@code rule_result}, el D4c.
 *
 * <p><b>No decide el expediente.</b> Produce un hallazgo, no una resolución: una exclusión bloquea
 * el Fast Track y deriva a revisión, pero la decisión sigue siendo del analista (CLAUDE.md #5,
 * human-in-the-loop). El resultado se audita en {@code rule_result} tanto en PASS como en FAIL.
 */
@Service
public class CoverageRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CoverageRuleEvaluator.class);
    private static final String COVERAGE_EXCLUSION = "COVERAGE_EXCLUSION";

    /**
     * @param excluded {@code true} si alguna exclusión dura aplica al hecho generador del claim.
     * @param findings una fila por regla evaluada (PASS/FAIL), para auditar en {@code rule_result}.
     */
    public record Result(boolean excluded, List<RuleFinding> findings) {}

    public Result evaluate(ClaimReport claim, BusinessRules rules) {
        List<BusinessRules.EvaluableRule> evaluableRules = rules.evaluableRules();
        if (evaluableRules == null || evaluableRules.isEmpty()) {
            return new Result(false, List.of());
        }

        List<RuleFinding> findings = new ArrayList<>();
        boolean excluded = false;

        for (BusinessRules.EvaluableRule rule : evaluableRules) {
            if (!COVERAGE_EXCLUSION.equals(rule.ruleType())) {
                continue;
            }
            boolean causeExcluded = claim.claimCauseId() != null
                    && rule.excludedClaimCauseIds() != null
                    && rule.excludedClaimCauseIds().contains(claim.claimCauseId());
            // PASS = la cobertura cubre el hecho generador (regla satisfecha);
            // FAIL = lo excluye (la regla dispara).
            findings.add(new RuleFinding(
                    rule.id(),
                    rule.ruleType(),
                    !causeExcluded,
                    "claimCause=" + claim.claimCause() + " (id=" + claim.claimCauseId() + ")"));
            if (causeExcluded) {
                excluded = true;
            }
        }

        if (excluded) {
            log.info("[CoverageRuleEvaluator] Hecho generador '{}' (id={}) excluido por la cobertura {} — bloquea Fast Track",
                    claim.claimCause(), claim.claimCauseId(), claim.coverageId());
        }
        return new Result(excluded, findings);
    }

    /** Motivos legibles para el analista, a partir de los findings que fallaron. */
    public List<String> excludedReasons(Result result, ClaimReport claim) {
        return result.findings().stream()
                .filter(f -> !f.passed())
                .map(f -> "La cobertura no cubre el hecho generador declarado ("
                        + claim.claimCause() + ") — exclusión configurada por la aseguradora")
                .toList();
    }
}
