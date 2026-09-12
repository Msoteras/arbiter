package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;

/**
 * Spanish labels for the exported files — the one place outside the frontend that translates enum
 * literals. The JSON the preview reads keeps the literals and the frontend maps them (estado.ts,
 * clasificacion.ts), but a CSV or a PDF is a document someone opens on their own, with no frontend
 * in between to translate it. Keep these in step with those two files.
 */
final class ReportLabels {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final long MINUTES_PER_HOUR = 60;
    private static final long MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR;

    private ReportLabels() {
    }

    static String status(CaseStatus status) {
        return switch (status) {
            case PENDING_CLASSIFICATION -> "Pendiente de clasificación";
            case PENDING_ANALYST_REVIEW -> "Pendiente de revisión";
            case CLASSIFICATION_FAILED -> "Clasificación fallida";
            case AWAITING_DOCUMENTATION -> "Falta documentación";
            case PENDING_EXPERT_REPORT -> "Derivado a peritaje";
            case APPROVED -> "Aprobado";
            case REJECTED -> "Rechazado";
            case LAPSED -> "Caducado";
        };
    }

    static String classification(Classification classification) {
        if (classification == null) {
            return "Sin clasificación";
        }
        return switch (classification) {
            case FAST_TRACK -> "Fast Track";
            case FALTA_DOCUMENTACION -> "Falta documentación";
            case LLM_RECOMIENDA_APROBAR -> "Recomienda aprobar";
            case LLM_NO_RECOMIENDA_APROBAR -> "Recomienda rechazar";
            case LLM_SOLICITA_REVISION_MANUAL -> "Requiere revisión manual";
        };
    }

    /**
     * Both spellings, because both are in the data: {@code ClassificationResultsService} normalizes
     * to APPROVE/REJECT when it writes, but rows recorded before that normalization — and the demo
     * seed — hold APROBAR/RECHAZAR. Reading only the English ones showed the Spanish rows raw in the
     * file. Same tolerance {@code CaseServiceImpl} already has when it applies a decision.
     */
    static String decision(String decision) {
        if (decision == null) {
            return "Sin decisión";
        }
        return switch (decision) {
            case "APPROVE", "APROBAR" -> "Aprobó";
            case "REJECT", "RECHAZAR" -> "Rechazó";
            default -> decision;
        };
    }

    static String claimCauseFilter(String claimCause) {
        return claimCause == null ? "Todos" : claimCause;
    }

    /** "45 min", "3 h 20 min", "2 d 5 h" — the same format the preview table shows. */
    static String duration(long minutes) {
        if (minutes < MINUTES_PER_HOUR) {
            return minutes + " min";
        }
        if (minutes < MINUTES_PER_DAY) {
            long hours = minutes / MINUTES_PER_HOUR;
            long rest = minutes % MINUTES_PER_HOUR;
            return rest == 0 ? hours + " h" : hours + " h " + rest + " min";
        }
        long days = minutes / MINUTES_PER_DAY;
        long hours = (minutes % MINUTES_PER_DAY) / MINUTES_PER_HOUR;
        return hours == 0 ? days + " d" : days + " d " + hours + " h";
    }

    /** Hours with one decimal and a decimal comma, so Excel in es-AR reads it as a number. */
    static String hours(long minutes) {
        return BigDecimal.valueOf(minutes)
                .divide(BigDecimal.valueOf(MINUTES_PER_HOUR), 1, RoundingMode.HALF_UP)
                .toPlainString()
                .replace('.', ',');
    }
}
