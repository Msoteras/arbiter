package ar.edu.utn.frba.arbiter.common.enums;

/**
 * What the external expert concluded after verifying a claim. Unlike {@link Classification} it is
 * evidence from a person, not a model recommendation, but it still doesn't resolve the case: the
 * analyst decides.
 */
public enum ExpertVerdict {
    FRAUD_CONFIRMED,
    FRAUD_DISCARDED,
    /**
     * Includes the expert never getting to inspect (the insured never presented the item). Not the
     * same as a verification that found nothing wrong.
     */
    INCONCLUSIVE
}
