package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * The dashboard's optional cuts; null means "every one". The insurer is deliberately absent: it comes
 * from the caller's token and must never be a parameter.
 *
 * @param analystId the analyst the case is ASSIGNED to, not who decided it: open cases have no decision
 *                  and would drop out of every figure
 */
public record MetricsFilter(Long branchId, Long analystId) {

    public static final MetricsFilter NONE = new MetricsFilter(null, null);

    public boolean isEmpty() {
        return branchId == null && analystId == null;
    }
}
