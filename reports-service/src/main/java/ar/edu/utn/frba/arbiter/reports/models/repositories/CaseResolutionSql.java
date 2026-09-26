package ar.edu.utn.frba.arbiter.reports.models.repositories;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;

/**
 * Resolution date and time waiting on third parties, shared so the dashboard and the resolution
 * report agree. {@link #RESOLUTION_CTE} opens the {@code WITH}; the rest join with {@code ",\n"}.
 */
final class CaseResolutionSql {

    /**
     * Resolved means the current status is final; the date is the LAST transition into it, picked before
     * any period filter so a closing undone by a reopen is not counted.
     */
    static final String RESOLUTION_CTE = """
            WITH resolution AS (
                SELECT DISTINCT ON (h.case_id) h.case_id, h.changed_at AS resolved_at
                  FROM case_status_history h
                  JOIN cases c ON c.id = h.case_id AND h.final_status_id = c.current_status_id
                 ORDER BY h.case_id, h.changed_at DESC, h.id DESC
            )""";

    /**
     * Seconds each resolved case spent in a {@link CaseStatus#pausingTheTerm()} status ({@code :pausing}),
     * clipped to its reported/resolved window; {@code GREATEST(..., 0)} drops stretches outside it.
     */
    static final String WAITING_CTE = """
            ordered AS (
                SELECT h.case_id,
                       h.changed_at AS from_at,
                       LEAD(h.changed_at) OVER (
                           PARTITION BY h.case_id ORDER BY h.changed_at, h.id) AS to_at,
                       st.name AS status
                  FROM case_status_history h
                  JOIN case_status st ON st.id = h.final_status_id
            ),
            waiting AS (
                SELECT o.case_id,
                       SUM(GREATEST(EXTRACT(EPOCH FROM (
                           LEAST(COALESCE(o.to_at, r.resolved_at), r.resolved_at)
                           - GREATEST(o.from_at, w.reported_at))), 0)) AS waiting_seconds
                  FROM ordered o
                  JOIN resolution r ON r.case_id = o.case_id
                  JOIN cases w      ON w.id = o.case_id
                 WHERE o.status IN (:pausing)
                 GROUP BY o.case_id
            )""";

    private CaseResolutionSql() {
    }
}
