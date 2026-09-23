package ar.edu.utn.frba.arbiter.reports.dto;

/**
 * How often the analyst decided where the model pointed. Only claims with an actionable
 * recommendation (approve / don't approve) count; manual review, missing documentation and Fast
 * Track have nothing to agree with.
 *
 * @param rate null when nothing counted: unknown, not zero
 */
public record RecommendationAgreement(long decided, long agreed, Double rate) {

    public static final RecommendationAgreement EMPTY = new RecommendationAgreement(0, 0, null);

    public static RecommendationAgreement of(long decided, long agreed) {
        return new RecommendationAgreement(decided, agreed, decided == 0 ? null : (double) agreed / decided);
    }
}
