package ar.edu.utn.frba.arbiter.common.enums;

/**
 * What backs a fraud record against an insured. Only {@link #EXPERT_BACKED} feeds the risk score
 * and can veto Fast Track: letting an unverified suspicion score would feed back on itself (today's
 * high score raises tomorrow's), which is exactly what Ley 25.326 makes expensive to get wrong.
 */
public enum FraudRecordSource {

    /** The originating case has an {@code expert_assessment} with {@link ExpertVerdict#FRAUD_CONFIRMED}. */
    EXPERT_BACKED,

    /** Determined by the analyst without an expert report. Visible to the next analyst, never scored. */
    ANALYST_DECLARED
}
