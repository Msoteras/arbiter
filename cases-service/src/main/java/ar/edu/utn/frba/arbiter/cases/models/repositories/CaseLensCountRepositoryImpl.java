package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.services.CaseStatusService;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public class CaseLensCountRepositoryImpl implements CaseLensCountRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public LensCounts countLenses(Specification<Case> spec, Long me) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Case> root = query.from(Case.class);
        // Explicit LEFT join: the implicit one from root.get("analyst") is INNER and would drop
        // unassigned cases from every other count.
        Join<Object, Object> analyst = root.join("analyst", JoinType.LEFT);

        // Same criterion as CaseSpecifications.scope. Counted here rather than filtered in the WHERE,
        // so every lifecycle row can be crossed with every ownership.
        Predicate closed = root.get("currentStatus").get("name").in(
                CaseStatusService.TERMINAL_STATUSES.stream().map(CaseStatus::name).toList());

        List<Selection<?>> cells = new ArrayList<>();
        for (Predicate lifecycle : List.of(cb.not(closed), closed)) {
            cells.add(countWhen(cb, lifecycle));
            cells.add(me == null
                    ? cb.literal(0L)
                    : countWhen(cb, cb.and(lifecycle, cb.equal(analyst.get("id"), me))));
            cells.add(countWhen(cb, cb.and(lifecycle, cb.isNotNull(root.get("analyst")))));
            cells.add(countWhen(cb, cb.and(lifecycle, cb.isNull(root.get("analyst")))));
            cells.add(countWhen(cb, cb.and(lifecycle,
                    root.get("riskBand").in(RiskBand.HIGH, RiskBand.CRITICAL))));
        }
        query.multiselect(cells);

        Predicate where = spec == null ? null : spec.toPredicate(root, query, cb);
        if (where != null) {
            query.where(where);
        }

        Tuple row = entityManager.createQuery(query).getSingleResult();
        return new LensCounts(ownership(row, 0), ownership(row, 5));
    }

    private static OwnershipCounts ownership(Tuple row, int from) {
        return new OwnershipCounts(value(row, from), value(row, from + 1), value(row, from + 2),
                value(row, from + 3), value(row, from + 4));
    }

    /** {@code sum(case when ... then 1 else 0)} because {@code count(*) filter} isn't standard JPA. */
    private static Expression<Long> countWhen(CriteriaBuilder cb, Predicate condition) {
        return cb.sum(cb.<Long>selectCase().when(condition, 1L).otherwise(0L));
    }

    private static long value(Tuple row, int index) {
        Number count = row.get(index, Number.class);
        return count == null ? 0 : count.longValue();
    }
}
