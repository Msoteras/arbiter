package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
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
                                                    String insuredId, LocalDate eventDateFrom, LocalDate eventDateTo) {
        return Stream.of(
                        status(status),
                        claimCause(claimCause),
                        policyNumber(policyNumber),
                        insuredId(insuredId),
                        eventDateFrom(eventDateFrom),
                        eventDateTo(eventDateTo)
                )
                .filter(Objects::nonNull)
                .reduce(Specification::and)
                .orElse(null); // sin filtros: JpaSpecificationExecutor trata null como "sin restricción"
    }

    private static Specification<Case> status(CaseStatus status) {
        return status == null ? null
                : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    private static Specification<Case> claimCause(String claimCause) {
        return claimCause == null || claimCause.isBlank() ? null
                : (root, query, cb) -> cb.equal(root.get("claimCause"), claimCause);
    }

    private static Specification<Case> policyNumber(String policyNumber) {
        return policyNumber == null || policyNumber.isBlank() ? null
                : (root, query, cb) -> cb.equal(root.get("policyNumber"), policyNumber);
    }

    private static Specification<Case> insuredId(String insuredId) {
        return insuredId == null || insuredId.isBlank() ? null
                : (root, query, cb) -> cb.equal(root.get("insuredId"), insuredId);
    }

    private static Specification<Case> eventDateFrom(LocalDate from) {
        return from == null ? null
                : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("eventDate"), from.atStartOfDay());
    }

    private static Specification<Case> eventDateTo(LocalDate to) {
        // Inclusive del día completo: estrictamente antes de la medianoche del día siguiente.
        return to == null ? null
                : (root, query, cb) -> cb.lessThan(root.get("eventDate"), to.plusDays(1).atStartOfDay());
    }
}
