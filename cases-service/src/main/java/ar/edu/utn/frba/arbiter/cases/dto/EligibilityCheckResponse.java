package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * Whether {@code POST /cases} would accept this claim right now, with the same rules as
 * {@code PolicyEligibilityValidator}, so the wizard can stop the insured before they upload documents.
 */
public record EligibilityCheckResponse(boolean eligible, String reason) {

    public static EligibilityCheckResponse ok() {
        return new EligibilityCheckResponse(true, null);
    }

    public static EligibilityCheckResponse notEligible(String reason) {
        return new EligibilityCheckResponse(false, reason);
    }
}
