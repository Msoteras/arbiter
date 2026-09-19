package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSignal;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSummary;
import ar.edu.utn.frba.arbiter.reports.services.FraudSummaries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

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
            case PENDING_REPAIR -> "Derivado a reparación";
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

    /** How an optional filter reads in the exported document when it was left unset. */
    static String filterValue(String filter) {
        return filter == null ? "Todos" : filter;
    }

    /**
     * The alert level of the fraud report — whether the score flagged the case, not what it scored.
     *
     * <p>A LOW or MEDIUM band reads "No alertó" and never as its band: a low score is not an
     * indicator of fraud, and printing "Bajo" under "Nivel de alerta" reads as "nothing to see"
     * about a case that is in the report precisely because something else was seen. "Sin evaluar"
     * is kept apart because there the scoring never ran at all.
     */
    static String alertLevel(String bucket) {
        return switch (bucket) {
            case "CRITICAL" -> "Crítico";
            case "HIGH" -> "Alto";
            case FraudSummary.NOT_FLAGGED -> "No alertó";
            case FraudSummary.NOT_SCORED -> "Sin evaluar";
            default -> bucket;
        };
    }

    static String alertLevel(RiskBand band) {
        return alertLevel(FraudSummaries.alertLevel(band));
    }

    /**
     * The signals of one case, each with the magnitude that makes it actionable — "3 imágenes con
     * coincidencia" says what to look at, "incoherencias forenses" only says it was flagged. Mirror
     * of {@code indicators()} in the frontend's fraud-report.ts; keep the two in step.
     */
    static String signals(FraudReportRow row) {
        return row.signals().stream().map(signal -> switch (signal) {
            case HIGH_RISK_SCORE -> "Score de riesgo alto";
            case FORENSIC_INCONSISTENCY -> row.suspiciousImages() == 1
                    ? "1 imagen con coincidencia"
                    : row.suspiciousImages() + " imágenes con coincidencia";
        }).collect(Collectors.joining(" · "));
    }

    /** The name of a signal on its own, for the distribution in the heading. */
    static String signal(String literal) {
        return switch (FraudSignal.valueOf(literal)) {
            case HIGH_RISK_SCORE -> "Score de riesgo alto";
            case FORENSIC_INCONSISTENCY -> "Incoherencias forenses";
        };
    }

    /** The analyst's determination, which is the only column of the fraud report that asserts one. */
    static String fraudDetermination(FraudReportRow row) {
        if (!row.fraudDetermined()) {
            return "No";
        }
        return row.expertBacked() ? "Sí · con respaldo pericial" : "Sí";
    }

    /**
     * A rate as a whole percentage — "38%". The report states the Fast Track share next to the count
     * it came from, so a decimal place would add noise, not precision.
     */
    static String percent(Double rate) {
        return rate == null ? "—" : Math.round(rate * 100) + "%";
    }

    /**
     * A rate with at most one decimal and a decimal comma — "14,3%", "15%". The fraud report's rates
     * are small, so a whole number would flatten them ("3,6%" and "4,4%" both read "4%"), and it is
     * also what its preview shows: the screen and the file must state the same figure.
     */
    static String percentWithOneDecimal(Double rate) {
        if (rate == null) {
            return "—";
        }
        return BigDecimal.valueOf(rate * 100)
                .setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
                .replace('.', ',') + "%";
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
