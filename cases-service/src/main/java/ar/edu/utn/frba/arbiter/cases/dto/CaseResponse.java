package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Expediente detail read model. The risk fields are the cached parallel fraud score: {@code
 * riskScore} and {@code riskBand} are {@code null} when the claim wasn't scored ("sin scorear"),
 * which the fraud-gauge renders as "Sin datos" — distinct from a real {@code LOW}.
 *
 * <p>{@code forensicReport} carries the cached structured image-fraud analysis for the analyst's
 * forensic tab (H0009) — analyst-only, never shown to the insured. Null when no analysis ran
 * (Fast Track, or a case with no image attachments).
 */
public record CaseResponse(
        Long id,
        /**
         * De qué aseguradora es el expediente, sólo poblado en "mis siniestros" del asegurado —
         * la única vista que mezcla compañías. Hacen falta los dos: {@code insurerSlug} para
         * volver a pedirlo (el id de expediente es autoincremental por esquema, así que solo no
         * lo identifica) y {@code insurerName} para mostrarlo, porque dos siniestros con el mismo
         * número son indistinguibles si no se dice de quién es cada uno.
         *
         * <p>Se expone el slug y no el id de la aseguradora para no meter una clave de base en
         * una URL. Null para el analista, que trabaja dentro de una sola compañía.
         */
        String insurerSlug,
        String insurerName,
        CaseStatus status,
        String branch,
        String product,
        String claimCause,
        String insuredItem,
        String insuredId,
        /** Nullable hasta que la primera clasificación resuelve (ver Case.insuredName). */
        String insuredName,
        String policyNumber,
        String description,
        LocalDateTime eventDate,
        String eventLocation,
        BigDecimal claimedAmount,
        Classification analysisClassification,
        double analysisConfidence,
        String analysisDetail,
        Double riskScore,
        RiskBand riskBand,
        List<RiskBreakdownItem> riskBreakdown,
        ImageForensicReport forensicReport,
        /**
         * Analista dueño del expediente, por su id de {@code claims_analyst} — local al esquema
         * de la aseguradora, no comparable entre tenants. Null = sin asignar.
         */
        Long assignedAnalystId,
        /** Nombre del analista asignado, resuelto por el join con {@code claims_analyst}. */
        String assignedAnalystName,
        Instant createdAt,
        Instant updatedAt,
        /** Full transition trail with timestamps; null on list endpoints (only GET /{id} loads it). */
        List<StatusTransitionResponse> statusHistory
) {
}
