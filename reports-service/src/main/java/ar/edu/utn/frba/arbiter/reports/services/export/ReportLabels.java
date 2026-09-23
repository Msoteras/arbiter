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
 * Spanish labels for exported files, which have no frontend to translate enum literals. Keep in step
 * with the frontend's estado.ts and clasificacion.ts.
 */
final class ReportLabels {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final long MINUTES_PER_HOUR = 60;
    private static final long MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR;

    /**
     * Minimum previous-period cases for a delta to be printed; below it one case swings a rate by ten
     * points. Same threshold as the frontend's KPI cards.
     */
    private static final int MIN_COMPARISON_BASE = 5;

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

    /** Both spellings are in the data: APPROVE/REJECT from the app, APROBAR/RECHAZAR from older rows and seeds. */
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

    static String filterValue(String filter) {
        return filter == null ? "Todos" : filter;
    }

    /**
     * Whether the score flagged the case, not what it scored: LOW/MEDIUM read "No alertó" rather than
     * their band, since the case is listed for another signal.
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
     * Each signal with the magnitude that makes it actionable; the document signal travels verbatim.
     * Mirrors {@code indicators()} in the frontend's fraud-report.ts.
     */
    static String signals(FraudReportRow row) {
        return row.signals().stream().map(signal -> switch (signal) {
            case HIGH_RISK_SCORE -> "Score de riesgo alto";
            case FORENSIC_INCONSISTENCY -> row.suspiciousImages() == 1
                    ? "1 imagen con coincidencia"
                    : row.suspiciousImages() + " imágenes con coincidencia";
            case DOCUMENT_INCONSISTENCY -> row.documentInconsistencyNote();
        }).collect(Collectors.joining(" · "));
    }

    static String signal(String literal) {
        return switch (FraudSignal.valueOf(literal)) {
            case HIGH_RISK_SCORE -> "Score de riesgo alto";
            case FORENSIC_INCONSISTENCY -> "Incoherencias forenses";
            case DOCUMENT_INCONSISTENCY -> "Contradicción con la documentación";
        };
    }

    static String fraudDetermination(FraudReportRow row) {
        if (!row.fraudDetermined()) {
            return "No";
        }
        return row.expertBacked() ? "Sí · con respaldo pericial" : "Sí";
    }

    /** Whole percentage; the count is printed next to it, so a decimal adds noise. */
    static String percent(Double rate) {
        return rate == null ? "—" : Math.round(rate * 100) + "%";
    }

    /**
     * One decimal with a decimal comma: the fraud rates are small and whole numbers would flatten them.
     * Matches the preview so the screen and the file state the same figure.
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

    /** "45 min", "3 h 20 min", "2 d 5 h": the same format the preview shows. */
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

    /** "(+2)", "(-1)", "(=)", or "" below {@link #MIN_COMPARISON_BASE}. */
    static String countDelta(long current, long previous, long previousBase) {
        if (previousBase < MIN_COMPARISON_BASE) {
            return "";
        }
        long change = current - previous;
        return change == 0 ? " (=)" : " (%s%d)".formatted(change > 0 ? "+" : "", change);
    }

    /** Change in percentage points, "(+3,9 pp)"; "" below the base or when either rate is null. */
    static String rateDelta(Double current, Double previous, long previousBase) {
        if (previousBase < MIN_COMPARISON_BASE || current == null || previous == null) {
            return "";
        }
        BigDecimal points = BigDecimal.valueOf((current - previous) * 100).setScale(1, RoundingMode.HALF_UP);
        if (points.signum() == 0) {
            return " (=)";
        }
        String sign = points.signum() > 0 ? "+" : "";
        return " (%s%s pp)".formatted(sign, points.stripTrailingZeros().toPlainString().replace('.', ','));
    }

    /** "(+2 h)", "(-1 d 3 h)", "(=)"; "" below the base or when either average is null. */
    static String durationDelta(Double currentMinutes, Double previousMinutes, long previousBase) {
        if (previousBase < MIN_COMPARISON_BASE || currentMinutes == null || previousMinutes == null) {
            return "";
        }
        long change = Math.round(currentMinutes - previousMinutes);
        if (change == 0) {
            return " (=)";
        }
        return " (%s%s)".formatted(change > 0 ? "+" : "-", duration(Math.abs(change)));
    }
}
