package ar.edu.utn.frba.arbiter.classification.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Builder
public record InsuredPolicy(
        String policyNumber,
        String insuredName,
        String insuredId,
        String branch,
        String product,
        /** El bien cubierto, tal como lo tiene la aseguradora (no lo que declaró el asegurado). */
        String insuredItem,
        /**
         * IMEI del equipo cuando el ramo lo tiene (Celulares); null donde no aplica. Es el operando
         * contra el que se cruza el IMEI que aparece en los documentos adjuntos (D4b).
         */
        String imei,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        boolean upToDate,
        BigDecimal insuredAmount,
        BigDecimal deductible,
        List<PolicyCoverage> coverages,
        List<String> applicableClauses
) {

    /**
     * Si la póliza cubría temporalmente esa fecha. Vive acá porque la ventana de vigencia es de la
     * póliza y ya la preguntan dos lugares: la regla dura D13 ({@code TemporalRuleEvaluator}) y la
     * foto que se audita ({@code policy_snapshot.in_force}). Duplicada, un día uno de los dos
     * empieza a usar {@code isAfter} donde el otro usa {@code isBefore} y nadie se entera.
     *
     * <p>Sin fechas devuelve {@code false}: no se afirma vigencia que no se pudo verificar — mismo
     * criterio que el dueño de la póliza en D2. El dato crudo, nulls incluidos, queda igual en
     * {@code policy_snapshot.insurer_db_payload}.
     */
    public boolean inForceOn(LocalDate date) {
        if (date == null || effectiveFrom == null || effectiveTo == null) {
            return false;
        }
        return !date.isBefore(effectiveFrom) && !date.isAfter(effectiveTo);
    }

    @Builder
    public record PolicyCoverage(
            String code,
            String description,
            BigDecimal insuredAmount,
            BigDecimal deductible
    ) {}
}
