package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reglas duras temporales/de frecuencia, evaluadas por código (no interpretadas por el LLM). Hoy:
 * <ul>
 *   <li><b>D13</b> — vigencia de la póliza: el hecho tiene que caer dentro de {@code effectiveFrom..
 *       effectiveTo}.</li>
 *   <li><b>D11</b> — plazo de denuncia: {@code reportedAt - occurredAt} no puede superar el
 *       {@code report_deadline_hours} de la cobertura.</li>
 *   <li><b>D10</b> — tope de eventos por año: contar los siniestros del asegurado en el ramo dentro
 *       de los últimos 12 meses; el actual no puede superar {@code max_events_per_year}.</li>
 * </ul>
 *
 * <p>Estas reglas <b>bloquean el Fast Track</b> y aportan motivos legibles para el analista, en vez
 * de mandar el texto al LLM y que lo interprete. No cierran el expediente (human-in-the-loop): son
 * hallazgos, no una resolución. Cada regla solo se evalúa si tiene los datos que necesita; si falta
 * alguno, no participa (no bloquea a ciegas).
 *
 * <p><b>Auditoría (rule_result):</b> a diferencia de las exclusiones de cobertura, estos límites son
 * columnas de {@code coverage}, no filas de {@code insurer_rule}, y {@code rule_result.rule_id} es FK
 * NOT NULL a {@code insurer_rule} — así que por ahora no se auditan en esa tabla (haría falta
 * modelarlos como reglas de aseguradora, igual que las exclusiones). Ver plan-reglas-evaluables.md §1.1.
 */
@Service
public class TemporalRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TemporalRuleEvaluator.class);

    /**
     * @param blocksFastTrack {@code true} si alguna regla temporal falló (no debe fast-trackear).
     * @param reasons         motivos legibles de las reglas que fallaron, para el analista.
     */
    public record Result(boolean blocksFastTrack, List<String> reasons) {}

    public Result evaluate(ClaimReport claim, InsuredPolicy policy, InsuredHistory history, BusinessRules rules) {
        List<String> reasons = new ArrayList<>();

        evaluatePolicyInForce(claim, policy, reasons);
        evaluateReportDeadline(claim, rules, reasons);
        evaluateMaxAnnualEvents(claim, history, rules, reasons);

        boolean block = !reasons.isEmpty();
        if (block) {
            log.info("[TemporalRuleEvaluator] Reglas temporales incumplidas (bloquean Fast Track): {}", reasons);
        }
        return new Result(block, reasons);
    }

    /** D13 · el hecho tiene que estar dentro de la vigencia de la póliza. */
    private void evaluatePolicyInForce(ClaimReport claim, InsuredPolicy policy, List<String> reasons) {
        if (claim.eventDate() == null || policy.effectiveFrom() == null || policy.effectiveTo() == null) {
            return;
        }
        LocalDate eventDate = claim.eventDate().toLocalDate();
        if (eventDate.isBefore(policy.effectiveFrom()) || eventDate.isAfter(policy.effectiveTo())) {
            reasons.add(String.format(
                    "El siniestro (%s) ocurrió fuera de la vigencia de la póliza (%s a %s)",
                    eventDate, policy.effectiveFrom(), policy.effectiveTo()));
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
     * D10 · tope de eventos por año. Cuenta los siniestros previos del asegurado en el mismo ramo
     * dentro de los 12 meses anteriores al hecho; el actual no puede superar el tope. El historial es
     * por asegurado (no trae número de póliza), así que se acota por ramo como mejor aproximación a
     * "por póliza".
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
