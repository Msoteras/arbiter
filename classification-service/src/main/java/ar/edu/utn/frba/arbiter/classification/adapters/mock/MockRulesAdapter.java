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
@Profile({"dev", "test", "default"})
public class MockRulesAdapter implements RulesAdapter {

    /**
     * Default fraud-scoring config for the mock, meant as a faithful H0012 reference config.
     * Only the three factors with real logic are active, reweighted to sum 1 for readability
     * (amount ratio, claim frequency, policy standing). {@code PURCHASE_TO_REPORT_TIME} and
     * {@code DOCUMENT_INCONSISTENCY} stay implemented but out of the active set until they have
     * real logic/data — one returns a fixed 0.0 and the other uses the policy's start date as a
     * proxy for the purchase date, so activating them would bias the score without adding signal.
     * Bands use the documented H0012 cuts (Bajo / Medio / Alto / Crítico on a 0..1 scale).
     * Insurers override this via their own rules; here every mocked branch shares it so the
     * scoring flow has data to run against.
     */
    private static final BusinessRules.ScoringConfig DEFAULT_SCORING_CONFIG = BusinessRules.ScoringConfig.builder()
            .factors(List.of(
                    factor(RiskFactorIds.AMOUNT_RATIO, 0.45),
                    factor(RiskFactorIds.CLAIM_FREQUENCY, 0.35),
                    factor(RiskFactorIds.POLICY_STANDING, 0.20)))
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

    private static final Map<String, BusinessRules> RULES_BY_BRANCH = Map.of(
            "Celulares|Robo en vía pública", BusinessRules.builder()
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
                    .build(),

            "Celulares|Hurto", BusinessRules.builder()
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
                    .build(),

            "Celulares|Rotura accidental", BusinessRules.builder()
                    .branchId("Celulares")
                    .claimCauseId("Rotura accidental")
                    .rules(List.of(
                            "Rotura accidental cubierta con franquicia del 20% del costo de reparación",
                            "Se requiere presupuesto de servicio técnico autorizado por la marca",
                            "Si el costo de reparación supera el 70% del valor del bien, se considera pérdida total"
                    ))
                    .exclusions(List.of(
                            "Daño por líquido",
                            "Rotura intencional",
                            "Equipo con intervención previa no autorizada (root, jailbreak, reparación informal)"
                    ))
                    .fastTrackCriteria(List.of(
                            "Presupuesto de servicio técnico autorizado adjunto",
                            "Costo de reparación menor al 50% del valor asegurado",
                            "Sin siniestros previos del mismo tipo en los últimos 6 meses"
                    ))
                    .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                            .maxClaimedAmountRatio(0.5)
                            .requiresUpToDatePolicy(true)
                            .build())
                    .requiredDocumentTypes(List.of("item_photo", "quote"))
                    .scoringConfig(DEFAULT_SCORING_CONFIG)
                    .build()
    );

    @Override
    public BusinessRules getRules(String branchId, String claimCauseId) {
        String key = branchId + "|" + claimCauseId;
        return RULES_BY_BRANCH.getOrDefault(key, defaultGenericRules(branchId, claimCauseId));
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
