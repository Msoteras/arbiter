package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hasta dónde llega la cobertura: a quién alcanza y si le queda saldo (D9). Son dos columnas de
 * {@code coverage} que se guardaban y no leía nadie.
 *
 * <ul>
 *   <li><b>{@code covers_family_group}</b> — si la cobertura no alcanza al grupo familiar y el
 *       damnificado es un familiar, el hecho no está cubierto.</li>
 *   <li><b>{@code claim_exhausts_coverage}</b> — si un siniestro liquidado agota la cobertura, el
 *       siguiente sobre la misma póliza ya no tiene con qué responder.</li>
 * </ul>
 *
 * <p><b>Por qué el primero no lo decide el LLM.</b> Saber de quién era el equipo exige leer el
 * relato, y leer es lo único que el código no puede hacer — pero interpretar la regla sí. Así que se
 * parte en dos, igual que en D4a: la pasada de extracción devuelve un <b>hecho tipado</b>
 * ({@link DocumentExtraction.AffectedParty}) y acá se evalúa la regla. El modelo nunca decide si hay
 * cobertura; solo aporta el dato.
 *
 * <p><b>Fuente del dato: la cobertura, no la póliza.</b> {@code coverage.covers_family_group} (lo que
 * configura el referente) y {@code poliza.cubre_grupo_familiar} (BD Aseguradora) existen las dos y ya
 * se contradicen en el seed. Manda la del referente (decisión de Fede, 10/08).
 *
 * <p>Como el resto de las reglas duras: <b>bloquean el Fast Track y aportan motivos</b>, no cierran
 * el expediente. Una exclusión no rechaza la liquidación sola — el analista firma (CLAUDE.md #5).
 */
@Service
public class CoverageScopeEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CoverageScopeEvaluator.class);

    /** Estado de resolución que indica que el siniestro previo efectivamente consumió la cobertura. */
    private static final String SETTLED = "LIQUIDADO";

    /** @param reasons motivos legibles de las reglas que fallaron, para el analista. */
    public record Result(boolean blocksFastTrack, List<String> reasons) {}

    public Result evaluate(
            ClaimReport claim,
            InsuredHistory history,
            BusinessRules rules,
            Map<String, DocumentExtraction> documents) {

        List<String> reasons = new ArrayList<>();

        evaluateFamilyGroup(rules, documents, reasons);
        evaluateExhaustedCoverage(claim, history, rules, reasons);

        boolean block = !reasons.isEmpty();
        if (block) {
            log.info("[CoverageScopeEvaluator] Alcance de cobertura incumplido (bloquea Fast Track): {}", reasons);
        }
        return new Result(block, reasons);
    }

    /**
     * Solo dispara con un {@code FAMILIAR} explícito. {@code DESCONOCIDO} —o ningún documento leído—
     * deja la regla sin evaluar: que el papel no aclare de quién era el equipo no puede costarle la
     * cobertura a nadie.
     */
    private void evaluateFamilyGroup(
            BusinessRules rules, Map<String, DocumentExtraction> documents, List<String> reasons) {
        if (!Boolean.FALSE.equals(rules.coversFamilyGroup())) {
            return; // la cobertura alcanza al grupo familiar, o no está configurada
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
     * Cuenta solo los siniestros liquidados <b>de la misma póliza</b>: la cobertura se agota por
     * póliza, y el mismo asegurado puede tener otras. Sin número de póliza en el historial la regla
     * no participa, en vez de contar siniestros ajenos a esta cobertura.
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
}
