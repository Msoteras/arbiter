package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import org.springframework.data.jpa.domain.Specification;

public interface CaseLensCountRepository {

    /**
     * All lens counts in a single query with conditional aggregation: the endpoint's cost is round
     * trips, not database work. {@code spec} must not include the active tab's scope.
     *
     * @param me the caller's analyst id, or null if they have no analyst profile (the referente)
     */
    LensCounts countLenses(Specification<Case> spec, Long me);

    record LensCounts(long all, long mine, long assigned, long unassigned, long fraud, long open, long closed) {
    }
}
