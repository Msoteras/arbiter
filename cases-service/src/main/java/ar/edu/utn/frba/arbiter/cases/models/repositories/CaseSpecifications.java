package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Specs combinables para el filtrado de {@link Case} en {@code GET /api/v1/cases}. Cada método
 * devuelve {@code null} cuando el filtro no aplica; {@link #withFilters} descarta los nulls antes
 * de combinar (ojo: {@link Specification#allOf} NO los descarta solo — con Spring Data JPA 4.0.4
 * tira {@code IllegalArgumentException: Other specification must not be null} si alguna es null),
 * así el service no necesita condicionales para armar el where dinámico.
 *
 * <p>No hay spec de aseguradora: depende de auth-service (rol del usuario autenticado) y del
 * esquema multi-tenant, ninguno de los dos levantado todavía (ver GAPS-FLUJO.md, Gap F).
 */
public final class CaseSpecifications {

    private CaseSpecifications() {
    }

    public static Specification<Case> withFilters(CaseStatus status, String claimCause, String policyNumber,
                                                    String insuredId, LocalDate eventDateFrom, LocalDate eventDateTo,
                                                    String q, RiskBand riskBand) {
        return Stream.of(
                        status(status),
                        claimCause(claimCause),
                        policyNumber(policyNumber),
                        insuredId(insuredId),
                        eventDateFrom(eventDateFrom),
                        eventDateTo(eventDateTo),
                        freeText(q),
                        riskBand(riskBand)
                )
                .filter(Objects::nonNull)
                .reduce(Specification::and)
                .orElse(null); // sin filtros: JpaSpecificationExecutor trata null como "sin restricción"
    }

    private static Specification<Case> status(CaseStatus status) {
        // Filtra por el nombre del estado, no por el id de catálogo: el id es un detalle de la
        // tabla y el enum sigue siendo el vocabulario del filtro que llega por query param.
        return status == null ? null
                : (root, query, cb) -> cb.equal(root.get("currentStatus").get("name"), status.name());
    }

    private static Specification<Case> claimCause(String claimCause) {
        return claimCause == null || claimCause.isBlank() ? null
                : (root, query, cb) -> cb.equal(root.get("claimCause").get("name"), claimCause);
    }

    private static Specification<Case> policyNumber(String policyNumber) {
        return policyNumber == null || policyNumber.isBlank() ? null
                : (root, query, cb) -> cb.equal(root.get("policy").get("externalPolicyNumber"), policyNumber);
    }

    /** El {@code insuredId} del filtro es el DNI, que es como lo pide el analista. */
    private static Specification<Case> insuredId(String insuredId) {
        return insuredId == null || insuredId.isBlank() ? null
                : (root, query, cb) -> cb.equal(root.get("insured").get("dni"), insuredId);
    }

    private static Specification<Case> riskBand(RiskBand riskBand) {
        return riskBand == null ? null
                : (root, query, cb) -> cb.equal(root.get("riskBand"), riskBand);
    }

    private static Specification<Case> eventDateFrom(LocalDate from) {
        return from == null ? null
                : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from.atStartOfDay());
    }

    private static Specification<Case> eventDateTo(LocalDate to) {
        // Inclusive del día completo: estrictamente antes de la medianoche del día siguiente.
        return to == null ? null
                : (root, query, cb) -> cb.lessThan(root.get("occurredAt"), to.plusDays(1).atStartOfDay());
    }

    /**
     * Búsqueda de texto libre (H0011): OR entre los identificadores que existen de verdad —
     * número de expediente ({@code id}, match exacto — nadie busca un expediente por "contiene
     * este dígito"), número de póliza, y asegurado (por DNI o por nombre completo), estos dos
     * últimos case-insensitive por substring. No busca por causa del siniestro: esa ya tiene su
     * propio filtro exacto, para no mezclar "encontrar un expediente puntual" con "filtrar por
     * tipo de siniestro".
     *
     * <p>El nombre se arma concatenando {@code name} y {@code surname} para que "laura fernández"
     * matchee igual que cualquiera de los dos por separado.
     */
    private static Specification<Case> freeText(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String trimmed = q.trim();
        String pattern = "%" + trimmed.toLowerCase() + "%";
        Long idMatch = parseAsId(trimmed);
        return (root, query, cb) -> {
            var insured = root.get("insured");
            Predicate byPolicyNumber = cb.like(
                    cb.lower(root.get("policy").get("externalPolicyNumber")), pattern);
            Predicate byDni = cb.like(cb.lower(insured.get("dni")), pattern);
            Predicate byInsuredName = cb.like(
                    cb.lower(cb.concat(cb.concat(insured.get("name"), " "), insured.get("surname"))),
                    pattern);
            if (idMatch == null) {
                return cb.or(byPolicyNumber, byDni, byInsuredName);
            }
            return cb.or(cb.equal(root.get("id"), idMatch), byPolicyNumber, byDni, byInsuredName);
        };
    }

    private static Long parseAsId(String q) {
        try {
            return Long.parseLong(q);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
