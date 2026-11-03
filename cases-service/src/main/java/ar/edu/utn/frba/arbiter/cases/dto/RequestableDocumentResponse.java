package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * A branch document the analyst can still ask for.
 *
 * @param suggestion why the system suggests it; null when it doesn't. Internal: the insured never
 *                   learns the reason, least of all the risk band
 */
public record RequestableDocumentResponse(String code, String name, Suggestion suggestion) {

    public enum Suggestion {
        /** Every case of this claim cause needs it. */
        SCHEDULE,
        /** The schedule asks for it from the case's risk band up. */
        RISK
    }
}
