package ar.edu.utn.frba.arbiter.cases.models.entities;

import ar.edu.utn.frba.arbiter.common.enums.RiskBand;

/**
 * A document the analyst asked the insured for. Kept after it arrives: an
 * {@code other_<n>} upload is only named by its {@code description}, and the model reads its
 * {@code reason} on every later classification.
 *
 * @param type        a catalog code, or {@code other_<n>} for a document the catalog doesn't name
 * @param description what the insured is asked for; only for {@code other_<n>}
 * @param reason      why it was asked and what to check in it; internal, never shown to the insured
 * @param fulfilled   whether the insured already uploaded it
 * @param riskBand    the case's band when it was asked for: a later suggestion only adds what a
 *                    higher band needs
 */
public record RequestedDocument(String type, String description, String reason, boolean fulfilled,
                                RiskBand riskBand) {

    public static final String OTHER_PREFIX = "other_";

    public static boolean isOther(String type) {
        return type != null && type.startsWith(OTHER_PREFIX);
    }

    public RequestedDocument markFulfilled() {
        return new RequestedDocument(type, description, reason, true, riskBand);
    }
}
