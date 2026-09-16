package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * How often the analyst landed where the model pointed. The metric that says whether the
 * recommendation is worth reading — and the one Disposición SSN 2/2023 is really about: the model
 * recommends, a person decides, and this counts how often the person decided otherwise.
 *
 * <p>Low agreement is not a failure to fix by nudging analysts toward the model. It is information:
 * either the model is miscalibrated for this insurer's portfolio, or it is catching things the
 * analysts are dismissing. Both are worth looking at; neither is settled by this number alone.
 *
 * <p><b>Only claims with an actionable recommendation count.</b> "Requiere revisión manual" and
 * "falta documentación" say nothing about approving or rejecting, so a claim carrying one has
 * nothing to agree or disagree with and stays out of both numbers — as does every Fast Track, which
 * the model never saw.
 *
 * @param decided  claims decided in the period whose recommendation pointed one way or the other
 * @param agreed   how many of those ended where the model pointed
 * @param rate     agreed over decided; null when nothing decided in the period had an actionable
 *                 recommendation — unknown, not zero
 */
public record RecommendationAgreement(long decided, long agreed, Double rate) {

    public static final RecommendationAgreement EMPTY = new RecommendationAgreement(0, 0, null);

    public static RecommendationAgreement of(long decided, long agreed) {
        return new RecommendationAgreement(decided, agreed, decided == 0 ? null : (double) agreed / decided);
    }
}
