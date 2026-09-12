package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * The two cuts the dashboard offers on top of the period. Both optional; null means "every one".
 *
 * <p>The insurer is deliberately NOT here. That one is never a parameter — it comes from the
 * caller's token — and putting it next to these would invite someone to pass it.
 *
 * @param branchId  branch ("ramo") of the claim's cause
 * @param analystId the analyst the case is ASSIGNED to, which is what the inbox's filter means too.
 *                  Not "who decided it": an open case has no decision and would drop out of every
 *                  figure, which is the opposite of what someone filtering by analyst wants to see.
 */
public record MetricsFilter(Long branchId, Long analystId) {

    public static final MetricsFilter NONE = new MetricsFilter(null, null);

    public boolean isEmpty() {
        return branchId == null && analystId == null;
    }
}
