package ar.edu.utn.frba.arbiter.common.enums;

/**
 * What backs a fraud record against an insured. The distinction is the whole point of the record:
 * the two sources carry different evidence, so they get different weight.
 *
 * <p>Only {@link #EXPERT_BACKED} feeds the risk score and can veto Fast Track. A suspicion the
 * analyst wrote down without an expert behind it stays an alert for whoever reviews the next
 * claim. Letting it score would close a loop on itself — today's high score raises tomorrow's,
 * and the person has no way out of it — which is exactly what Ley 25.326 makes expensive to get
 * wrong.
 */
public enum FraudRecordSource {

    /**
     * There is an {@code expert_assessment} on the originating case with
     * {@link ExpertVerdict#FRAUD_CONFIRMED}: a person inspected the claim and verified the fraud.
     */
    EXPERT_BACKED,

    /**
     * The analyst determined the fraud without a report behind it — a confession, or evidence
     * plain enough that deriving it would have been theatre. Visible to the next analyst, never
     * counted by the engine.
     */
    ANALYST_DECLARED
}
