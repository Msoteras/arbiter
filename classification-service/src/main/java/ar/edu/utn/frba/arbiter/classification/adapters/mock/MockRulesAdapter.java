package ar.edu.utn.frba.arbiter.classification.adapters.mock;

import ar.edu.utn.frba.arbiter.classification.adapters.RulesAdapter;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Profile({"dev", "test", "default"})
public class MockRulesAdapter implements RulesAdapter {

    private static final Map<String, BusinessRules> RULES_BY_BRANCH = Map.of(
            "Celulares|Robo en vía pública", BusinessRules.builder()
                    .branchId("Celulares")
                    .claimCauseId("Robo en vía pública")
                    .rules(List.of(
                            "El bien debe estar dentro del campo visual del insured al momento del robo",
                            "Se requiere report policial dentro de las 48 hs del hecho",
                            "El insured debe acreditar titularidad del equipo (factura o documentación)",
                            "Si el insured tiene más de 2 claims en los últimos 12 meses, derivar a investigación"
                    ))
                    .exclusions(List.of(
                            "Robo en domicilio declarado en póliza (se cubre por ramo Hogar)",
                            "Equipo fuera de la República Argentina",
                            "Robo con participación del insured o grupo conviviente"
                    ))
                    .fastTrackCriteria(List.of(
                            "Primer claim del insured",
                            "Report policial presentada dentro de las 24 hs",
                            "Monto reclamado inferior al 50% de la suma asegurada",
                            "Existencia de testigos o cámaras de seguridad"
                    ))
                    .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                            .maxClaimedAmountRatio(0.5)
                            .maxPriorClaims(0)
                            .requiresUpToDatePolicy(true)
                            .build())
                    .build(),

            "Celulares|Hurto", BusinessRules.builder()
                    .branchId("Celulares")
                    .claimCauseId("Hurto")
                    .rules(List.of(
                            "Se requiere report policial dentro de las 48 hs del hecho",
                            "El insured debe describir las circunstancias del hurto con detalle",
                            "Se requiere bloqueo del IMEI ante la empresa de telefonía"
                    ))
                    .exclusions(List.of(
                            "Hurto por descuido en transporte público sin report",
                            "Equipo dejado sin supervisión en lugar público"
                    ))
                    .fastTrackCriteria(List.of(
                            "Primer claim del insured",
                            "Monto reclamado inferior al 30% de la suma asegurada"
                    ))
                    .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                            .maxClaimedAmountRatio(0.3)
                            .maxPriorClaims(0)
                            .requiresUpToDatePolicy(true)
                            .build())
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
                            "Costo de reparación menor al 50% del valor insured",
                            "Sin claims previos del mismo tipo en los últimos 6 meses"
                    ))
                    .fastTrackThresholds(BusinessRules.FastTrackThresholds.builder()
                            .maxClaimedAmountRatio(0.5)
                            .requiresUpToDatePolicy(true)
                            .build())
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
                        "Se requiere report policial si corresponde al tipo de hecho"
                ))
                .exclusions(List.of())
                .fastTrackCriteria(List.of())
                .build();
    }
}
