package ar.edu.utn.frba.arbiter.common.enums;

/**
 * Whether the insured's free-text account matches the claim cause they picked from the wizard's
 * selector. The model reads the account against the branch's closed claim-cause catalog; what the
 * verdict implies is decided in code (CLAUDE.md #4: coverage is a hard rule, never the LLM's call).
 *
 * <p>Three values and not a boolean on purpose: "me robaron" is how people describe a robo, a hurto
 * and an olvido alike, so a two-way verdict would route half the honest claims. Only
 * {@link #CONTRADICTS} changes where the claim goes.
 */
public enum CauseConsistency {
    /** The account describes the declared claim cause. Nothing changes. */
    MATCHES,
    /**
     * The account doesn't settle it — too short, too vague, or it fits more than one cause.
     * Informational only: it adds a factor for the analyst and never alters the classification.
     */
    AMBIGUOUS,
    /**
     * The account positively describes a <b>different</b> cause from the branch's catalog, and the
     * sentence that says so is quoted. This is the only verdict that reroutes the claim.
     */
    CONTRADICTS
}
