package ar.edu.utn.frba.arbiter.reports.models.repositories;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;

/**
 * The two definitions every figure about resolution times is built on: when a case closed, and how
 * much of that time it spent waiting on somebody outside the insurer.
 *
 * <p>They live here because the dashboard and the resolution report both answer them, and they have
 * to answer them the same way. {@code ClaimMetricsRepository} used to carry its own copy with a
 * comment saying it was "defined exactly as ResolvedCaseRepository defines it" — which is the kind
 * of agreement that holds until someone edits one of the two. Sharing the text is what makes the
 * dashboard's average and the report's average the same number over the same period.
 *
 * <p>Both are CTEs to be concatenated: {@link #RESOLUTION_CTE} opens the {@code WITH}, and anything
 * after it joins with {@code ",\n"}.
 */
final class CaseResolutionSql {

    /**
     * When a case closed: it sits in a final status of the platform catalog ({@code is_final}, not a
     * hardcoded list), and the resolution date is the LAST transition into that status — a case that
     * was reopened and closed again counts once, on the day it closed for good.
     *
     * <p>The latest transition is picked before filtering by period on purpose: filtering first
     * would pick up an earlier closing that a reopen already undid.
     */
    static final String RESOLUTION_CTE = """
            WITH resolution AS (
                SELECT DISTINCT ON (h.case_id) h.case_id, h.changed_at AS resolved_at
                  FROM case_status_history h
                  JOIN cases c ON c.id = h.case_id AND h.final_status_id = c.current_status_id
                 ORDER BY h.case_id, h.changed_at DESC, h.id DESC
            )""";

    /**
     * Per resolved case, how many seconds it spent waiting on somebody outside the insurer:
     * documents from the insured, an expert's report, the repair shop's equipment. The statuses are
     * {@link CaseStatus#pausingTheTerm()}, bound as {@code :pausing}; it depends on
     * {@link #RESOLUTION_CTE} and is concatenated after it.
     *
     * <p>Each stretch of the history is clipped to the case's own window ({@code GREATEST} /
     * {@code LEAST}): a wait that started before the claim was filed, or that was still open when
     * the case closed, counts only for the part inside. The {@code GREATEST(..., 0)} drops the
     * stretches that fall entirely outside, which would otherwise subtract.
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
