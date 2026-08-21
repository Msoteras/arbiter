package ar.edu.utn.frba.arbiter.cases.dto;

/**
 * Whether {@code POST /cases} would accept this denuncia right now — same rules as the intake
 * gate ({@code PolicyEligibilityValidator}), just without creating anything. Lets the wizard block
 * "Siguiente" (or warn) before the insured fills out the rest of the form and uploads documents,
 * instead of finding out only at the very end.
 */
public record EligibilityCheckResponse(boolean eligible, String reason) {

    public static EligibilityCheckResponse ok() {
        return new EligibilityCheckResponse(true, null);
    }

    public static EligibilityCheckResponse notEligible(String reason) {
        return new EligibilityCheckResponse(false, reason);
    }
}
