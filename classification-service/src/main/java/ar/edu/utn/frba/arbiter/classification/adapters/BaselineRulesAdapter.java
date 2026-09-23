package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The platform's baseline rule set, onto which {@link RulesRestAdapter} overlays what the referente
 * configured. Not a test double: every section the insurer hasn't configured falls back to it. It is
 * the whole answer only in the {@code test} profile, where {@link RulesRestAdapter} is excluded.
 */
@Component
public class BaselineRulesAdapter implements RulesAdapter {

    /**
     * Image factors can stay active: non-evaluable factors are dropped from the weighted average, so
     * image-less claims aren't diluted. {@code PURCHASE_TO_REPORT_TIME} is left out because it uses the
     * policy start date as a proxy for the purchase date (biased score); {@code DOCUMENT_INCONSISTENCY}
     * is left out because enabling it is a per-insurer decision. Weights are normalized by the engine.
     */
    private static final BusinessRules.ScoringConfig DEFAULT_SCORING_CONFIG = BusinessRules.ScoringConfig.builder()
            .factors(List.of(
                    factor(RiskFactorIds.AMOUNT_RATIO, 0.45),
                    factor(RiskFactorIds.CLAIM_FREQUENCY, 0.35),
                    factor(RiskFactorIds.POLICY_STANDING, 0.20),
                    factor(RiskFactorIds.IMAGE_REUSE, 0.50),
                    factor(RiskFactorIds.IMAGE_WEB_MATCH, 0.40)))
            .bands(List.of(
                    band(RiskBand.LOW, 0.00),
                    band(RiskBand.MEDIUM, 0.30),
                    band(RiskBand.HIGH, 0.60),
                    band(RiskBand.CRITICAL, 0.80)))
            .build();

    private static BusinessRules.ScoringConfig.FactorWeight factor(String id, double weight) {
        return BusinessRules.ScoringConfig.FactorWeight.builder().factorId(id).weight(weight).build();
    }

    private static BusinessRules.ScoringConfig.Band band(RiskBand band, double minScoreInclusive) {
        return BusinessRules.ScoringConfig.Band.builder().band(band).minScoreInclusive(minScoreInclusive).build();
    }

    /** Ids mirror {@code init-multitenant.sql}'s seed: they end up in {@code rule_result.rule_id}. */
    private static BusinessRules.EvaluableRule temporalRule(long id, RuleType type) {
        return BusinessRules.EvaluableRule.builder()
                .id(id)
                .ruleType(type.name())
                // A failed hard rule never rejects on its own: it routes the claim to the analyst.
                .effect("DERIVAR")
                .blocksFastTrack(true)
                .build();
    }

    /** The only hard rule with its own threshold; the others take theirs from the coverage. */
    private static BusinessRules.EvaluableRule policeDeadlineRule(long id, long deadlineHours) {
        return BusinessRules.EvaluableRule.builder()
                .id(id)
                .ruleType(RuleType.POLICE_DEADLINE.name())
                .effect("DERIVAR")
                .blocksFastTrack(true)
                .deadlineHours(deadlineHours)
                .build();
    }

    // Keyed by coverage id (insurer rules are scoped by branch + coverage, not by claim cause).
    // Seed ids: 1 = cellphone robbery, 2 = theft (hurto). Anything else falls back to defaultGenericRules.
    private static final Map<Long, BusinessRules> RULES_BY_COVERAGE = Map.of(
            1L, BusinessRules.builder()
                    .branchId("Celulares")
                    .claimCauseId("Robo en vía pública")
                    .rules(List.of(
                            "El bien debe estar dentro del campo visual del asegurado al momento del robo",
                            "Se requiere denuncia policial dentro de las 48 hs del hecho",
                            "El asegurado debe acreditar titularidad del equipo (factura o documentación)",
                            "Si el asegurado tiene más de 2 siniestros en los últimos 12 meses, derivar a investigación"
                    ))
                    .exclusions(List.of(
                            "Robo en domicilio declarado en póliza (se cubre por ramo Hogar)",
                            "Equipo fuera de la República Argentina",
                            "Robo con participación del asegurado o grupo conviviente"
                    ))
                    .fastTrackCriteria(List.of(
                            "Primer siniestro del asegurado",
                            "Denuncia policial presentada dentro de las 24 hs",
                            "Monto reclamado inferior al 50% de la suma asegurada",
                            "Existencia de testigos o cámaras de seguridad"
                    ))
                    .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                            .maxClaimedAmountRatio(0.5)
                            .maxPriorClaims(0)
                            .requiresUpToDatePolicy(true)
                            .requiredDocumentTypes(List.of("police_report"))
                            .build())
                    .requiredDocumentTypes(List.of("police_report"))
                    .scoringConfig(DEFAULT_SCORING_CONFIG)
                    .reportDeadlineHours(72L)
                    .maxEventsPerYear(2)
                    .waitingPeriodDays(30)
                    // Mirrors the seed: the robbery coverage excludes the theft (hurto) claim cause (id 3).
                    // Temporal rules are only evaluated when their row exists.
                    .evaluableRules(List.of(
                            BusinessRules.EvaluableRule.builder()
                                    .id(3L)
                                    .ruleType(RuleType.COVERAGE_EXCLUSION.name())
                                    .effect("RECHAZAR")
                                    .blocksFastTrack(true)
                                    .excludedClaimCauseIds(List.of(3L))
                                    .build(),
                            temporalRule(4L, RuleType.POLICY_IN_FORCE),
                            temporalRule(5L, RuleType.WAITING_PERIOD),
                            temporalRule(6L, RuleType.REPORT_DEADLINE),
                            policeDeadlineRule(7L, 72L),
                            temporalRule(8L, RuleType.MAX_EVENTS_YEAR)))
                    .build(),

            2L, BusinessRules.builder()
                    .branchId("Celulares")
                    .claimCauseId("Hurto")
                    .rules(List.of(
                            "Se requiere denuncia policial dentro de las 48 hs del hecho",
                            "El asegurado debe describir las circunstancias del hurto con detalle",
                            "Se requiere bloqueo del IMEI ante la empresa de telefonía"
                    ))
                    .exclusions(List.of(
                            "Hurto por descuido en transporte público sin denuncia",
                            "Equipo dejado sin supervisión en lugar público"
                    ))
                    .fastTrackCriteria(List.of(
                            "Primer siniestro del asegurado",
                            "Monto reclamado inferior al 30% de la suma asegurada"
                    ))
                    .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                            .maxClaimedAmountRatio(0.3)
                            .maxPriorClaims(0)
                            .requiresUpToDatePolicy(true)
                            .requiredDocumentTypes(List.of("police_report"))
                            .build())
                    .requiredDocumentTypes(List.of("police_report"))
                    .scoringConfig(DEFAULT_SCORING_CONFIG)
                    .reportDeadlineHours(72L)
                    .maxEventsPerYear(1)
                    .waitingPeriodDays(30)
                    .evaluableRules(List.of(
                            temporalRule(9L, RuleType.POLICY_IN_FORCE),
                            temporalRule(10L, RuleType.WAITING_PERIOD),
                            temporalRule(11L, RuleType.REPORT_DEADLINE),
                            policeDeadlineRule(12L, 72L),
                            temporalRule(13L, RuleType.MAX_EVENTS_YEAR)))
                    .build()
    );

    @Override
    public BusinessRules getRules(String branchId, Long coverageId, String claimCauseId) {
        BusinessRules rules = coverageId == null ? null : RULES_BY_COVERAGE.get(coverageId);
        return withFraudRecordPolicy(rules != null ? rules : defaultGenericRules(branchId, claimCauseId));
    }

    /** Never configured in the baseline: a Fast Track veto weighs on a person, so only the insurer turns it on (Ley 25.326). */
    @Override
    public BusinessRules.FraudRecordPolicy getFraudRecordPolicy() {
        return BusinessRules.FraudRecordPolicy.unconfigured();
    }

    private BusinessRules withFraudRecordPolicy(BusinessRules rules) {
        return rules.fraudRecordPolicy() != null
                ? rules
                : rules.toBuilder().fraudRecordPolicy(getFraudRecordPolicy()).build();
    }

    private BusinessRules defaultGenericRules(String branchId, String claimCauseId) {
        return BusinessRules.builder()
                .branchId(branchId)
                .claimCauseId(claimCauseId)
                .rules(List.of(
                        "Se requiere documentación respaldatoria del hecho",
                        "Se requiere denuncia policial si corresponde al tipo de hecho"
                ))
                .exclusions(List.of())
                .fastTrackCriteria(List.of())
                .scoringConfig(DEFAULT_SCORING_CONFIG)
                .build();
    }
}
