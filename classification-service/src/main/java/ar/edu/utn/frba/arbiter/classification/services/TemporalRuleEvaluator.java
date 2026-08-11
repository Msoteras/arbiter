package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li><b>D9</b> — carencia: el hecho no puede caer dentro de los {@code waiting_period_days}
 *       posteriores al alta de la póliza.</li>
 *   <li><b>D12</b> — plazo de la denuncia policial declarada. Umbral <b>provisorio</b> por
 *       propiedad (ver {@code policeReportDeadlineHours}), no configurable por aseguradora
 *       todavía.</li>
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
     * Plazo para hacer la denuncia policial, en horas desde el hecho (D12).
     *
     * <p><b>Provisorio y a sabiendas.</b> Debería ser configurable por la aseguradora igual que el
     * resto —decisión #12 del CLAUDE.md, y es la lección de D4a— pero hoy no tiene dónde vivir:
     * {@code coverage} tiene una sola columna de plazo ({@code report_deadline_hours}) y ya la usa
     * D11 para el plazo de denuncia <b>a la aseguradora</b>, que es otro plazo. Reusarla haría que
     * un número gobierne dos reglas distintas.
     *
     * <p>Queda como propiedad y no como constante para que al menos se pueda cambiar por entorno sin
     * recompilar. El fix de fondo es modelarlo como regla evaluable en {@code insurer_rule} —igual
     * que la exclusión de cobertura de D3, que además trae auditoría en {@code rule_result} gratis—
     * o sumar una columna al DER. Enhancement pendiente, decidido con Fede el 10/08.
     */
    private final long policeReportDeadlineHours;

    public TemporalRuleEvaluator(
            @Value("${arbiter.rules.police-report-deadline-hours:72}") long policeReportDeadlineHours) {
        this.policeReportDeadlineHours = policeReportDeadlineHours;
    }

    /**
     * @param blocksFastTrack {@code true} si alguna regla temporal falló (no debe fast-trackear).
     * @param reasons         motivos legibles de las reglas que fallaron, para el analista.
     */
    public record Result(boolean blocksFastTrack, List<String> reasons) {}

    public Result evaluate(ClaimReport claim, InsuredPolicy policy, InsuredHistory history, BusinessRules rules) {
        List<String> reasons = new ArrayList<>();

        evaluatePolicyInForce(claim, policy, reasons);
        evaluateWaitingPeriod(claim, policy, rules, reasons);
        evaluateReportDeadline(claim, rules, reasons);
        evaluatePoliceReportDeadline(claim, reasons);
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
        if (!policy.inForceOn(eventDate)) {
            reasons.add(String.format(
                    "El siniestro (%s) ocurrió fuera de la vigencia de la póliza (%s a %s)",
                    eventDate, policy.effectiveFrom(), policy.effectiveTo()));
        }
    }

    /**
     * D9 · carencia: la cobertura no aplica durante los primeros {@code waiting_period_days} desde
     * el alta de la póliza, aunque la póliza esté vigente. Existe para que no se contrate un seguro
     * por un hecho ya ocurrido o inminente.
     *
     * <p>Distinta de la vigencia (D13) aunque las dos miren las mismas fechas: la vigencia dice si
     * había contrato, la carencia dice si ese contrato ya daba cobertura. Y distinta del
     * {@code minPolicyAgeMonths} del Fast Track, que decide el <b>camino</b> (una póliza muy nueva
     * no va por vía expedita) y no el <b>derecho</b>: acá el siniestro directamente no está cubierto.
     */
    private void evaluateWaitingPeriod(
            ClaimReport claim, InsuredPolicy policy, BusinessRules rules, List<String> reasons) {
        if (rules.waitingPeriodDays() == null || claim.eventDate() == null || policy.effectiveFrom() == null) {
            return;
        }
        LocalDate eventDate = claim.eventDate().toLocalDate();
        LocalDate coverageStart = policy.effectiveFrom().plusDays(rules.waitingPeriodDays());
        if (eventDate.isBefore(coverageStart)) {
            reasons.add(String.format(
                    "El siniestro (%s) ocurrió dentro de la carencia de %d días: la cobertura recién "
                            + "aplica desde el %s (póliza dada de alta el %s)",
                    eventDate, rules.waitingPeriodDays(), coverageStart, policy.effectiveFrom()));
        }
    }

    /**
     * D12 · la denuncia policial no puede superar el plazo desde el hecho.
     *
     * <p>Se evalúa sobre lo que <b>declaró</b> el asegurado. Que la constancia diga otra fecha es
     * otra señal distinta, y la cruza {@code DocumentInconsistencyEvaluator}: una cosa es llegar
     * tarde y otra es declarar una fecha que el papel no respalda.
     *
     * <p>Sin {@code policeReportAt} la regla no participa: "no hubo denuncia policial" es un caso
     * legítimo (no todo hecho generador la lleva) y distinto de "hubo pero fuera de plazo".
     */
    private void evaluatePoliceReportDeadline(ClaimReport claim, List<String> reasons) {
        if (claim.policeReportAt() == null || claim.eventDate() == null) {
            return;
        }
        long hours = Duration.between(claim.eventDate(), claim.policeReportAt()).toHours();
        if (hours < 0) {
            reasons.add("La denuncia policial declarada es anterior a la fecha del hecho — dato inconsistente");
        } else if (hours > policeReportDeadlineHours) {
            reasons.add(String.format(
                    "Denuncia policial fuera de plazo: %d hs desde el hecho, supera el máximo de %d hs",
                    hours, policeReportDeadlineHours));
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
