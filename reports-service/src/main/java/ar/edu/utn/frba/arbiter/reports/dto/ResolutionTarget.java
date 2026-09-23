package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * The insurer's resolution goal (from rules-service) and how many decisions exceeded it. A management
 * goal the referent sets, not the legal deadline.
 *
 * @param enabled  false shows the average alone, same as when rules-service does not answer
 * @param exceeded decided cases whose handling time (total minus waiting on third parties) exceeded
 *                 the target; lapsed cases excluded. Zero while disabled
 */
public record ResolutionTarget(boolean enabled, Integer targetDays, long exceeded) {

    /** Not configured, or it could not be read. */
    public static final ResolutionTarget UNSET = new ResolutionTarget(false, null, 0);

    public ResolutionTarget withExceeded(long exceeded) {
        return new ResolutionTarget(enabled, targetDays, exceeded);
    }
}
