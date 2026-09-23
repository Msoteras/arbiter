package ar.edu.utn.frba.arbiter.common.enums;

/**
 * Whether the insured's free-text account matches the claim cause they picked in the wizard. The
 * model gives the verdict; what it implies is decided in code, since coverage is a hard rule.
 *
 * <p>Three values and not a boolean on purpose: "me robaron" describes a robo, a hurto and an
 * olvido alike, so a two-way verdict would reroute half the honest claims.
 */
public enum CauseConsistency {
    MATCHES,
    /** Too short, too vague, or fits more than one cause. Adds a factor, never alters the classification. */
    AMBIGUOUS,
    /** The account describes a different cause from the branch's catalog. The only verdict that reroutes the claim. */
    CONTRADICTS
}
