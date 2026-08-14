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
 * Evaluación determinística de las reglas duras de cobertura — hoy, las <b>inclusiones</b>: qué
 * hechos generadores SÍ cubre la cobertura del claim. Corre <b>antes</b> del gate de Fast Track (un
 * hecho generador no cubierto hace irrelevante al Fast Track) y sin LLM: es código que compara ids,
 * no interpretación del modelo. Cierra el D3 del handoff (nada validaba que el hecho generador
 * estuviera cubierto) y, junto con la escritura de {@code rule_result}, el D4c.
 *
 * <p>Antes esto era una lista NEGRA (COVERAGE_EXCLUSION): sin regla configurada, la cobertura cubría
 * todo por default. Se pasó a lista BLANCA porque ese default permisivo dejaba pasar sin avisar
 * denuncias de hechos generadores que la cobertura nunca tuvo que cubrir (ej. una caída sobre una
 * cobertura de robo) mientras nadie configurara nada — un fail-open peligroso. Ahora, sin regla
 * configurada para la cobertura, el hecho generador se considera NO cubierto.
 *
 * <p><b>No decide el expediente.</b> Produce un hallazgo, no una resolución: un hecho generador no
 * cubierto bloquea el Fast Track y deriva a revisión, pero la decisión sigue siendo del analista
 * (CLAUDE.md #5, human-in-the-loop). El resultado se audita en {@code rule_result} tanto en PASS
 * como en FAIL.
 */
@Service
public class CoverageRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CoverageRuleEvaluator.class);
    private static final String COVERAGE_INCLUSION = "COVERAGE_INCLUSION";

    /**
     * @param notCovered {@code true} si la cobertura tiene una regla de inclusión configurada y el
     *                   hecho generador del claim no está en su lista.
     * @param findings una fila por regla evaluada (PASS/FAIL), para auditar en {@code rule_result}.
     */
    public record Result(boolean notCovered, List<RuleFinding> findings) {}

    public Result evaluate(ClaimReport claim, BusinessRules rules) {
        List<BusinessRules.EvaluableRule> evaluableRules = rules.evaluableRules();
        if (evaluableRules == null || evaluableRules.isEmpty()) {
            return new Result(false, List.of());
        }

        List<RuleFinding> findings = new ArrayList<>();
        boolean notCovered = false;

        for (BusinessRules.EvaluableRule rule : evaluableRules) {
            if (!COVERAGE_INCLUSION.equals(rule.ruleType())) {
                continue;
            }
            // Una inclusión sin hechos generadores configurados no cubre nada: hay regla, así que sí
            // se evalúa y se audita (a diferencia de la exclusión vieja, acá la lista vacía importa).
            boolean causeIncluded = rule.includedClaimCauseIds() != null
                    && claim.claimCauseId() != null
                    && rule.includedClaimCauseIds().contains(claim.claimCauseId());
            // PASS = la cobertura cubre el hecho generador (está en la lista);
            // FAIL = no lo cubre (la regla dispara).
            findings.add(new RuleFinding(
                    rule.id(),
                    rule.ruleType(),
                    causeIncluded,
                    "claimCause=" + claim.claimCause() + " (id=" + claim.claimCauseId() + ")"));
            if (!causeIncluded) {
                notCovered = true;
            }
        }

        if (notCovered) {
            log.info("[CoverageRuleEvaluator] Hecho generador '{}' (id={}) no cubierto por la cobertura {} — bloquea Fast Track",
                    claim.claimCause(), claim.claimCauseId(), claim.coverageId());
        }
        return new Result(notCovered, findings);
    }

    /** Motivos legibles para el analista, a partir de los findings que fallaron. */
    public List<String> notCoveredReasons(Result result, ClaimReport claim) {
        return result.findings().stream()
                .filter(f -> !f.passed())
                .map(f -> "La cobertura no cubre el hecho generador declarado ("
                        + claim.claimCause() + ") — no está en la lista de hechos generadores cubiertos "
                        + "configurada por la aseguradora")
                .toList();
    }
}
