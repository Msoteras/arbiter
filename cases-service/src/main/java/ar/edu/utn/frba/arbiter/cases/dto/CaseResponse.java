package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.DeadlinePriority;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
        /**
         * Persona políticamente expuesta, tal como la declaró el asegurado al denunciar
         * (UIF/PLA). Es un dato de <b>debida diligencia</b>, no una señal de fraude: viaja para
         * que el analista lo vea junto al resto de los datos del asegurado, y a propósito no
         * entra al scoring ni al prompt (D16).
         */
        boolean pep,
        String policyNumber,
        String description,
        LocalDateTime eventDate,
        String eventLocation,
        BigDecimal claimedAmount,
        Classification analysisClassification,
        double analysisConfidence,
        /**
         * Los motivos detrás de {@code analysisClassification}, uno por fila — {@code llm_reason}
         * (classification-service) es una tabla de una fila por motivo, no una sola columna de
         * texto (DER), así que acá viaja igual: una lista, no un string armado con
         * {@code String.join}. Vacía cuando no hay motivos que mostrar (Fast Track, o sin
         * clasificación todavía).
         */
        List<String> analysisReasons,
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
        /** Fecha límite legal para expedirse (art. 56 Ley 17.418): denuncia + 30 días. */
        LocalDate responseDeadline,
        /**
         * Urgencia frente a ese plazo (semáforo de la bandeja). Derivada de {@code responseDeadline},
         * la fecha actual y si el expediente ya fue resuelto — no se persiste. {@code NONE} para
         * casos con más de 10 días o ya respondidos.
         */
        DeadlinePriority deadlinePriority,
        /** Full transition trail with timestamps; null on list endpoints (only GET /{id} loads it). */
        List<StatusTransitionResponse> statusHistory,
        /**
         * Lo que el modelo leyó de cada adjunto (H0031). Como {@code statusHistory}, sólo lo carga
         * {@code GET /{id}}: en un listado sería un join por fila. Vacía cuando el expediente no se
         * clasificó, se resolvió por Fast Track sin leer nada, o se clasificó antes de que esta
         * tabla existiera — la solapa simplemente no aparece.
         */
        List<DocumentAnalysisSummary> documentAnalyses
) {
}
