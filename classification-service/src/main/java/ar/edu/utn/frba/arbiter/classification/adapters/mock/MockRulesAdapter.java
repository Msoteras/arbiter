package ar.edu.utn.frba.arbiter.classification.adapters.mock;

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
                    // Handoff case 6 ("Hurto no cubierto"): the theft coverage (id 1) excludes
                    // el hecho generador Hurto (claim_cause id 3). Lista negra: un robo (cause 2) no
                    // se ve afectado. Espeja el seed COVERAGE_EXCLUSION de init-multitenant.sql.
                    .evaluableRules(List.of(BusinessRules.EvaluableRule.builder()
                            .id(3L)
                            .ruleType("COVERAGE_EXCLUSION")
                            .effect("RECHAZAR")
                            .blocksFastTrack(true)
                            .excludedClaimCauseIds(List.of(3L))
                            .build()))
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
                    .build()
    );

    @Override
    public BusinessRules getRules(String branchId, Long coverageId, String claimCauseId) {
        BusinessRules rules = coverageId == null ? null : RULES_BY_COVERAGE.get(coverageId);
        return rules != null ? rules : defaultGenericRules(branchId, claimCauseId);
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
