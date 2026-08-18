package ar.edu.utn.frba.arbiter.classification.adapters.mock;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.classification.adapters.RulesAdapter;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFactorIds;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MockRulesAdapter implements RulesAdapter {

    /**
     * Default fraud-scoring config for the mock, meant as a faithful H0012 reference config.
     * Active factors: amount ratio, claim frequency, policy standing (the three with real logic on
     * every claim) plus the two image-fraud factors ({@code IMAGE_REUSE}, {@code IMAGE_WEB_MATCH}).
     * The image factors are safe to keep active because the engine now drops non-evaluable factors
     * from the weighted average: on Fast Track / image-less claims they simply don't participate,
     * so they don't dilute the score — they only weigh in when there's an image analysis to grade.
     *
     * <p>{@code PURCHASE_TO_REPORT_TIME} stays out of the active set: it uses the policy's start date
     * as a proxy for the purchase date, so it would produce a real (biased) score from stubbed data
     * — a data-quality issue the non-evaluable exclusion doesn't fix. {@code DOCUMENT_INCONSISTENCY}
     * is a stub that now reports itself non-evaluable, so it would never contribute; left inactive.
     *
     * <p>Weights don't need to sum to 1 (the engine normalizes by total active weight). Bands use the
     * documented H0012 cuts (Bajo / Medio / Alto / Crítico). The image weights are provisional — an
     * insurer overrides all of this via its own rules.
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

    /**
     * An active hard temporal rule. The ids mirror the ones in {@code init-multitenant.sql}'s
     * seed: the baseline has to be swappable with whatever rules-service serves without changing
     * behavior, and in the case-bound flow that id ends up in {@code rule_result.rule_id}.
     */
    private static BusinessRules.EvaluableRule temporalRule(long id, RuleType type) {
        return BusinessRules.EvaluableRule.builder()
                .id(id)
                .ruleType(type.name())
                // A failed hard rule doesn't reject on its own (human-in-the-loop): it derives to
                // the analyst.
                .effect("DERIVAR")
                .blocksFastTrack(true)
                .build();
    }

    /**
     * The police-report deadline is the only hard rule with its own threshold (the rest take it
     * from the coverage). 72h was the value of the property that used to govern every insurer
     * before the rule became configurable; it stays as the mock's baseline.
     */
    private static BusinessRules.EvaluableRule policeDeadlineRule(long id, long deadlineHours) {
        return BusinessRules.EvaluableRule.builder()
                .id(id)
                .ruleType(RuleType.POLICE_DEADLINE.name())
                .effect("DERIVAR")
                .blocksFastTrack(true)
                .deadlineHours(deadlineHours)
                .build();
    }

    // Keyed by coverage id: the DER scopes regla_aseguradora by rama + cobertura, NOT by hecho
    // generador (getRules gets the claim cause separately, for the document-requirement axis).
    // Seed coverage ids: 1 = "Robo de celular" (robo en vía pública), 2 = "Hurto". A claim cause
    // with no coverage (e.g. rotura accidental) has no rule and falls back to defaultGenericRules.
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
                    // Coverage limits (D10/D11), mirroring the seed: 72 h deadline, 2 events/year.
                    .reportDeadlineHours(72L)
                    .maxEventsPerYear(2)
                    .waitingPeriodDays(30)
                    // Hard evaluable rules, mirroring init-multitenant.sql's seed. The exclusion is
                    // handoff case 6 ("Hurto not covered"): the robbery coverage (id 1) excludes
                    // the hecho generador Hurto (claim_cause id 3). Blacklist: a robbery (cause 2)
                    // isn't affected. The temporal ones ship with the insurer at full strength:
                    // without their row, the engine simply doesn't evaluate them.
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
                    // Coverage limits (D10/D11), mirroring the seed: 72 h deadline, 1 event/year.
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

    /**
     * Off in the baseline, unlike the scoring config: a fraud record weighs on a <b>person</b>, so
     * an insurer gets that behavior only by turning the rule on themselves (Ley 25.326). The mock
     * standing in for an unreachable rules-service must not be what starts counting someone's past
     * against them.
     */
    @Override
    public BusinessRules.FraudRecordPolicy getFraudRecordPolicy() {
        return BusinessRules.FraudRecordPolicy.disabled();
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
