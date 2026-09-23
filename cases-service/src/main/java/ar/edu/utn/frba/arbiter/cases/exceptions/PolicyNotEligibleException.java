package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * Outside the coverage period, within the waiting period, or inconsistent dates. Rejected at intake,
 * so no case is created: this is not an automatic resolution, which would break human-in-the-loop.
 */
public class PolicyNotEligibleException extends RuntimeException {

    public PolicyNotEligibleException(String reason) {
        super(reason);
    }
}
