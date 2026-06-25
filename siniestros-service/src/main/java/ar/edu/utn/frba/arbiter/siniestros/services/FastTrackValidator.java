package ar.edu.utn.frba.arbiter.siniestros.services;

import ar.edu.utn.frba.arbiter.siniestros.dto.DenunciaSiniestro;
import ar.edu.utn.frba.arbiter.siniestros.dto.HistorialAsegurado;
import ar.edu.utn.frba.arbiter.siniestros.dto.PolizaAsegurado;
import ar.edu.utn.frba.arbiter.siniestros.dto.BusinessRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validación determinística de Fast Track: corre antes de invocar al LLM y evalúa
 * los umbrales de {@link BusinessRules.FastTrackThresholds} contra los datos del
 * claim, la póliza y el historial del asegurado. No usa IA — son reglas evaluables.
 */
@Service
public class FastTrackValidator {

    private static final Logger log = LoggerFactory.getLogger(FastTrackValidator.class);

    public record Result(boolean fastTrack, List<String> reasons) {}

    /**
     * @param documentTexts texto OCR de los documentos ya adjuntados, indexados por tipo
     *                       (ej. "denuncia_policial" -> texto). Solo se evalúa la PRESENCIA
     *                       del documento requerido, no su contenido — leer e interpretar lo
     *                       que dice el documento es trabajo del LLM, no de este gate.
     */
    public Result evaluate(
            DenunciaSiniestro claim,
            PolizaAsegurado policy,
            HistorialAsegurado history,
            BusinessRules rules,
            Map<String, String> documentTexts
    ) {
        BusinessRules.FastTrackThresholds thresholds = rules.fastTrackThresholds();
        if (thresholds == null) {
            return new Result(false,
                    List.of("No hay criterios de Fast Track configurados para " + rules.branchId() + "/" + rules.claimCauseId()));
        }

        List<String> reasons = new ArrayList<>();
        boolean eligible = true;

        if (thresholds.maxClaimedAmountRatio() != null) {
            if (claim.claimedAmount() == null || policy.insuredAmount() == null || policy.insuredAmount().signum() == 0) {
                eligible = false;
                reasons.add("No se pudo evaluar el monto reclamado contra la suma asegurada");
            } else {
                double ratio = claim.claimedAmount().doubleValue() / policy.insuredAmount().doubleValue();
                if (ratio <= thresholds.maxClaimedAmountRatio()) {
                    reasons.add(String.format(
                            "Monto reclamado (%.1f%% de la suma asegurada) dentro del límite de Fast Track (%.1f%%)",
                            ratio * 100, thresholds.maxClaimedAmountRatio() * 100));
                } else {
                    eligible = false;
                    reasons.add(String.format(
                            "Monto reclamado (%.1f%% de la suma asegurada) supera el límite de Fast Track (%.1f%%)",
                            ratio * 100, thresholds.maxClaimedAmountRatio() * 100));
                }
            }
        }

        if (thresholds.maxPriorClaims() != null) {
            if (history.previousClaimsCount() <= thresholds.maxPriorClaims()) {
                reasons.add("Siniestros previos (" + history.previousClaimsCount()
                        + ") dentro del límite de Fast Track (" + thresholds.maxPriorClaims() + ")");
            } else {
                eligible = false;
                reasons.add("Siniestros previos (" + history.previousClaimsCount()
                        + ") supera el límite de Fast Track (" + thresholds.maxPriorClaims() + ")");
            }
        }

        if (Boolean.TRUE.equals(thresholds.requiresUpToDatePolicy())) {
            if (policy.upToDate()) {
                reasons.add("Póliza al día con sus pagos");
            } else {
                eligible = false;
                reasons.add("Póliza con pagos atrasados — no aplica Fast Track");
            }
        }

        if (thresholds.requiredDocumentTypes() != null && !thresholds.requiredDocumentTypes().isEmpty()) {
            List<String> missing = thresholds.requiredDocumentTypes().stream()
                    .filter(type -> documentTexts == null || documentTexts.get(type) == null || documentTexts.get(type).isBlank())
                    .toList();
            if (missing.isEmpty()) {
                reasons.add("Documentación requerida para Fast Track presente: " + thresholds.requiredDocumentTypes());
            } else {
                eligible = false;
                reasons.add("Falta documentación requerida para Fast Track: " + missing);
            }
        }

        log.info("[FastTrackValidator] policy='{}' eligible={} reasons={}", policy.policyNumber(), eligible, reasons);
        return new Result(eligible, reasons);
    }
}
