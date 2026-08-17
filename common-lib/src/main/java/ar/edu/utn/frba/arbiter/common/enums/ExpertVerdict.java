package ar.edu.utn.frba.arbiter.common.enums;

/**
 * What the external expert concluded after verifying a claim. Constants are in English (code
 * convention); the Spanish display label is a frontend concern.
 *
 * <p>Unlike {@link Classification}, this is not a recommendation from a model: it is evidence
 * produced by a person who inspected the claim. It still does not resolve the case — the analyst
 * decides (decisión de arquitectura #5), now with the expert's finding next to the model's.
 */
public enum ExpertVerdict {
    /** The expert verified the fraud. Grounds a rejection on a fact, not on a suspicion. */
    FRAUD_CONFIRMED,
    /** The expert found no fraud: the claim is what it says it is. */
    FRAUD_DISCARDED,
    /**
     * The expert could not conclude — including when they never got to inspect (the insured
     * never presented the item). A verification that produced no finding, which is itself worth
     * recording: it is not the same as one that found nothing wrong.
     */
    INCONCLUSIVE
}
