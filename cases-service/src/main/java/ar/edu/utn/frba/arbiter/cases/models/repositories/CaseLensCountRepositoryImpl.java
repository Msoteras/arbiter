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
import org.springframework.data.jpa.domain.Specification;

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

        Expression<Long> mine = me == null
                ? cb.literal(0L)
                : countWhen(cb, cb.equal(analyst.get("id"), me));

        // Same criterion as CaseSpecifications.scope. Counted here rather than filtered in the WHERE,
        // which already carries the active tab's scope: these counts must not depend on it.
        Predicate closed = root.get("currentStatus").get("name").in(
                CaseStatusService.TERMINAL_STATUSES.stream().map(CaseStatus::name).toList());

        query.multiselect(
                cb.count(root),
                mine,
                countWhen(cb, cb.isNotNull(root.get("analyst"))),
                countWhen(cb, cb.isNull(root.get("analyst"))),
                countWhen(cb, root.get("riskBand").in(RiskBand.HIGH, RiskBand.CRITICAL)),
                countWhen(cb, cb.not(closed)),
                countWhen(cb, closed));

        Predicate where = spec == null ? null : spec.toPredicate(root, query, cb);
        if (where != null) {
            query.where(where);
        }

        Tuple row = entityManager.createQuery(query).getSingleResult();
        return new LensCounts(
                value(row, 0), value(row, 1), value(row, 2), value(row, 3), value(row, 4),
                value(row, 5), value(row, 6));
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
