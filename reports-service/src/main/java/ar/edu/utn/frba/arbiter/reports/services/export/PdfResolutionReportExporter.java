package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;
import ar.edu.utn.frba.arbiter.reports.dto.ReportBranding;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionSummary;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionTimelinePoint;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.services.ReportBrandingService;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.BarListBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.Block;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.BulletsBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.CalloutBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ColumnsBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.DefinitionsBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.MatrixBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.MetaStripBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.PdfDocumentWriter;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ProseBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ReportDocument;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ReportTheme;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.SectionBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.StackBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.StatCardsBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.TableBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.TimelineChartBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.TitleBlock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** What the resolution report says; where it lands on the page is {@link PdfDocumentWriter}'s. */
@Component
@RequiredArgsConstructor
public class PdfResolutionReportExporter implements ResolutionReportExporter {

    private static final String TITLE = "Reporte de resolución de siniestros";
    private static final String KIND = "RES";

    private static final String SCOPE = "Incluye los expedientes cerrados dentro del período, con "
            + "sus tiempos, la clasificación sugerida por el sistema y la decisión del analista. No "
            + "incluye los expedientes que siguen abiertos.";

    private static final String METHOD = "Se tomaron los expedientes cuya fecha de resolución cae "
            + "dentro del período y cuyo ramo y tipo de siniestro entran en los filtros.";

    private static final List<MatrixBlock.Legend> MATRIX_LEGEND = List.of(
            new MatrixBlock.Legend("El analista decidió en el mismo sentido que la recomendación",
                    MatrixBlock.Tone.AGREEMENT),
            new MatrixBlock.Legend("El analista decidió en sentido contrario a la recomendación",
                    MatrixBlock.Tone.DEPARTURE),
            new MatrixBlock.Legend("No había recomendación que seguir o no seguir",
                    MatrixBlock.Tone.NEUTRAL));

    private static final List<DefinitionsBlock.Definition> GLOSSARY = List.of(
            new DefinitionsBlock.Definition("Siguió la recomendación",
                    "Se cuenta solo sobre los expedientes con una recomendación que se puede seguir "
                            + "o no. Fast Track y los que piden revisión manual quedan fuera: no "
                            + "sugieren una decisión, así que no hay nada con qué comparar."),
            new DefinitionsBlock.Definition("Fast Track",
                    "Expediente que el motor de reglas habilitó a resolver sin análisis del modelo."),
            new DefinitionsBlock.Definition("Clasificación",
                    "Sugerencia del sistema. El analista: puede apartarse, y la decisión "
                            + "queda registrada igual."),
            new DefinitionsBlock.Definition("Espera de terceros",
                    "Tiempo con el expediente detenido esperando al asegurado, al perito o al "
                            + "servicio técnico. Corre, pero no se le imputa a la gestión."),
            new DefinitionsBlock.Definition("Tiempo total",
                    "Diferencia entre la denuncia y la resolución, sin descontar esa espera."));

    private static final List<TableBlock.Column> COLUMNS = List.of(
            new TableBlock.Column("Nº", 6, false),
            new TableBlock.Column("Asegurado", 20, false),
            new TableBlock.Column("Siniestro", 19, false),
            new TableBlock.Column("Denuncia · cierre", 17, false),
            new TableBlock.Column("Tiempo", 11, true),
            new TableBlock.Column("Sistema · analista", 15, false),
            new TableBlock.Column("Estado final", 12, false));

    /** Enough ids to act on; past that the table below is the place to read them. */
    private static final int MAX_LISTED_DEVIATIONS = 6;
    /** How far past the average a case has to sit before it is worth naming as the slowest. */
    private static final double SLOW_MULTIPLE = 2;

    /** Column order of the cross-tab, and of the counters that feed it. */
    private static final int COLUMN_APPROVED = 0;
    private static final int COLUMN_REJECTED = 1;
    private static final int COLUMN_UNDECIDED = 2;

    private final Clock clock;
    private final ReportBrandingService reportBrandingService;

    @Override
    public ReportFormat format() {
        return ReportFormat.PDF;
    }

    @Override
    public byte[] export(ResolutionReport report) {
        ZoneId zone = clock.getZone();
        ReportBranding branding = reportBrandingService.current();
        String code = ReportChrome.code(KIND, report.generatedAt(), zone);
        return PdfDocumentWriter.render(new ReportDocument(
                TITLE,
                period(report),
                code,
                branding,
                ReportChrome.confidentiality(branding),
                blocks(report, zone, code)));
    }

    private static List<Block> blocks(ResolutionReport report, ZoneId zone, String code) {
        List<ResolutionReportRow> decided = decided(report.rows());
        List<Block> blocks = new ArrayList<>();

        blocks.add(new TitleBlock(
                TITLE, "Período " + period(report), code));
        blocks.add(new MetaStripBlock(List.of(
                MetaStripBlock.Cell.of("Ramo", ReportLabels.filterValue(report.branch())),
                MetaStripBlock.Cell.of("Tipo de siniestro",
                        ReportLabels.filterValue(report.claimCause())),
                ReportChrome.requestedBy(),
                ReportChrome.issuedAt(report.generatedAt(), zone))));
        blocks.add(CalloutBlock.scope(SCOPE));

        blocks.add(SectionBlock.of("Resumen del período"));
        blocks.add(new StatCardsBlock(cards(report, decided)));
        blocks.add(new ColumnsBlock(timeline(report), StackBlock.of(
                byStatus(report.summary()), byClaimCause(report.summary())), 0.54f));

        List<BulletsBlock.Bullet> bullets = bullets(report, decided);
        if (!bullets.isEmpty()) {
            blocks.add(new BulletsBlock("Para la lectura del referente", bullets));
        }

        blocks.add(table(report, zone));
        blocks.add(new ColumnsBlock(matrix(report.rows()),
                StackBlock.of(new ProseBlock("Cómo se produjo este informe", METHOD),
                        new DefinitionsBlock("Qué significa cada término", GLOSSARY)), 0.48f));
        blocks.add(ReportChrome.provenance(code, report.generatedAt(), zone));
        blocks.add(ReportChrome.signatures());
        return blocks;
    }

    // ── summary ─────────────────────────────────────────────────────────────────

    private static List<StatCardsBlock.Card> cards(ResolutionReport report,
                                                   List<ResolutionReportRow> decided) {
        ResolutionSummary summary = report.summary();
        return List.of(
                StatCardsBlock.Card.of("Expedientes resueltos", String.valueOf(summary.totalCases()),
                        outcomeNote(summary)),
                timeCard(summary, decided),
                StatCardsBlock.Card.of("Resueltos por Fast Track",
                        ReportLabels.percent(summary.fastTrackRate()),
                        "%d de %d".formatted(summary.fastTrackCases(), summary.totalCases())),
                agreementCard(report.rows()));
    }

    /** What the period closed as, in the same breath as how many: "4" alone says nothing. */
    private static String outcomeNote(ResolutionSummary summary) {
        if (summary.byStatus().isEmpty()) {
            return "sin expedientes en el período";
        }
        if (summary.byStatus().size() == 1) {
            return "todos " + plural(status(summary.byStatus().getFirst()));
        }
        return summary.byStatus().stream()
                .map(count -> count.count() + " " + (count.count() == 1
                        ? status(count).toLowerCase(Locale.ROOT)
                        : plural(status(count))))
                .reduce((a, b) -> a + " · " + b)
                .orElseThrow();
    }

    private static StatCardsBlock.Card timeCard(ResolutionSummary summary,
                                                List<ResolutionReportRow> decided) {
        if (summary.averageMinutes() == null || decided.isEmpty()) {
            return new StatCardsBlock.Card("Tiempo promedio", "Sin datos",
                    "ningún expediente decidido en el período", StatCardsBlock.Style.UNAVAILABLE);
        }
        List<Long> minutes = decided.stream().map(ResolutionReportRow::totalMinutes).sorted().toList();
        return StatCardsBlock.Card.of("Tiempo promedio",
                ReportLabels.duration(Math.round(summary.averageMinutes())),
                "mediana %s · máx. %s".formatted(
                        ReportLabels.duration(median(minutes)),
                        ReportLabels.duration(minutes.getLast())));
    }

    /**
     * A departure is a legitimate outcome, not an error. The card is toned because it is the figure
     * the referent has to look at, and says how many rather than scoring anybody.
     *
     * <p>The note spells out the denominator. "2 de 4" on a period of six closed cases is the right
     * figure and the wrong impression: Fast Track and a request for manual review suggest no
     * decision, so there is nothing for the analyst to have followed or departed from.
     */
    private static StatCardsBlock.Card agreementCard(List<ResolutionReportRow> rows) {
        long comparable = rows.stream().filter(row -> followed(row) != null).count();
        if (comparable == 0) {
            return new StatCardsBlock.Card("Siguió la recomendación", "Sin datos",
                    "ninguno de los %d tuvo recomendación que seguir".formatted(rows.size()),
                    StatCardsBlock.Style.UNAVAILABLE);
        }
        long agreed = rows.stream().filter(row -> Boolean.TRUE.equals(followed(row))).count();
        long deviations = comparable - agreed;
        String deviationNote = switch ((int) Math.min(deviations, 2)) {
            case 0 -> "sin desvíos";
            case 1 -> "1 desvío";
            default -> deviations + " desvíos";
        };
        return new StatCardsBlock.Card("Siguió la recomendación",
                "%d de %d".formatted(agreed, comparable),
                "%d de %d con recomendación · %s".formatted(comparable, rows.size(), deviationNote),
                deviations == 0 ? StatCardsBlock.Style.PLAIN : StatCardsBlock.Style.ALERT);
    }

    // ── charts ──────────────────────────────────────────────────────────────────

    private static Block timeline(ResolutionReport report) {
        // The bucket is already a date in the insurer's zone, so it needs no zone to render.
        DateTimeFormatter bucketFormat = report.granularity() == TimelineGranularity.MONTH
                ? DateTimeFormatter.ofPattern("MM/yyyy")
                : DateTimeFormatter.ofPattern("dd/MM");
        List<TimelineChartBlock.Point> points = report.timeline().stream()
                .map(point -> new TimelineChartBlock.Point(
                        bucketFormat.format(point.bucket()),
                        point.averageMinutes(),
                        closures(point)))
                .toList();
        return new TimelineChartBlock(
                "Tiempo promedio por " + bucketLabel(report.granularity()),
                points,
                minutes -> ReportLabels.duration(Math.round(minutes)),
                "Ningún expediente decidido en el período: no hay promedio que graficar.");
    }

    private static String closures(ResolutionTimelinePoint point) {
        if (point.resolved() == 0) {
            return "sin cierres";
        }
        return point.resolved() == 1 ? "1 cierre" : point.resolved() + " cierres";
    }

    private static String bucketLabel(TimelineGranularity granularity) {
        return switch (granularity) {
            case DAY -> "día de cierre";
            case WEEK -> "semana de cierre";
            case MONTH -> "mes de cierre";
        };
    }

    private static Block byStatus(ResolutionSummary summary) {
        List<BarListBlock.Bar> bars = summary.byStatus().stream()
                .map(count -> new BarListBlock.Bar(status(count), count.count(),
                        share(count.count(), summary.totalCases()),
                        tone(CaseStatus.valueOf(count.label()))))
                .toList();
        return new BarListBlock("Por estado final", bars, null);
    }

    private static Block byClaimCause(ResolutionSummary summary) {
        List<BarListBlock.Bar> bars = summary.byClaimCause().stream()
                .map(count -> new BarListBlock.Bar(count.label(), count.count(),
                        share(count.count(), summary.totalCases()), ReportTheme.INK_SOFT))
                .toList();
        return new BarListBlock("Por tipo de siniestro", bars, null);
    }

    // ── narrative ───────────────────────────────────────────────────────────────

    /**
     * Only facts read straight off the rows the report already lists — never an interpretation of
     * them. Each one is there because it is the row somebody would otherwise have to find by hand.
     */
    private static List<BulletsBlock.Bullet> bullets(ResolutionReport report,
                                                     List<ResolutionReportRow> decided) {
        List<BulletsBlock.Bullet> bullets = new ArrayList<>();
        List<ResolutionReportRow> deviations = report.rows().stream()
                .filter(row -> Boolean.FALSE.equals(followed(row)))
                .toList();

        if (deviations.size() == 1) {
            ResolutionReportRow row = deviations.getFirst();
            bullets.add(new BulletsBlock.Bullet(
                    "El expediente #%d se %s pese a que el sistema recomendaba lo contrario."
                            .formatted(row.caseId(),
                                    ReportLabels.decision(row.analystDecision())
                                            .toLowerCase(Locale.ROOT)),
                    ReportTheme.STATUS_DANGER));
        } else if (deviations.size() > 1) {
            String ids = deviations.stream()
                    .limit(MAX_LISTED_DEVIATIONS)
                    .map(row -> "#" + row.caseId())
                    .reduce((a, b) -> a + ", " + b)
                    .orElseThrow();
            bullets.add(new BulletsBlock.Bullet(
                    "%d expedientes se resolvieron en contra de la recomendación del sistema: %s%s."
                            .formatted(deviations.size(), ids,
                                    deviations.size() > MAX_LISTED_DEVIATIONS ? " y otros" : ""),
                    ReportTheme.STATUS_DANGER));
        }

        slowest(decided, report.summary()).ifPresent(row -> bullets.add(new BulletsBlock.Bullet(
                "El expediente #%d fue el más lento del período: %s, más del doble del promedio."
                        .formatted(row.caseId(), ReportLabels.duration(row.totalMinutes())),
                ReportTheme.STATUS_RISK)));

        soleAnalyst(decided).ifPresent(analyst -> bullets.add(new BulletsBlock.Bullet(
                "Los %d cierres decididos del período los resolvió el mismo analista, %s."
                        .formatted(decided.size(), analyst),
                ReportTheme.STATUS_INFO)));

        return bullets;
    }

    private static Optional<ResolutionReportRow> slowest(List<ResolutionReportRow> decided,
                                                                   ResolutionSummary summary) {
        if (decided.size() < 2 || summary.averageMinutes() == null) {
            return Optional.empty();
        }
        return decided.stream()
                .max(Comparator.comparingLong(ResolutionReportRow::totalMinutes))
                .filter(row -> row.totalMinutes() > summary.averageMinutes() * SLOW_MULTIPLE);
    }

    private static Optional<String> soleAnalyst(List<ResolutionReportRow> decided) {
        if (decided.size() < 2) {
            return Optional.empty();
        }
        List<String> analysts = decided.stream()
                .map(ResolutionReportRow::analystName)
                .distinct()
                .toList();
        return analysts.size() == 1 && analysts.getFirst() != null
                ? Optional.of(analysts.getFirst())
                : Optional.empty();
    }

    // ── detail ──────────────────────────────────────────────────────────────────

    private static TableBlock table(ResolutionReport report, ZoneId zone) {
        List<TableBlock.Row> rows = report.rows().stream().map(row -> row(row, zone)).toList();
        return new TableBlock(
                "Detalle de expedientes cerrados",
                caption(report.rows().size()),
                COLUMNS,
                rows,
                "No hay expedientes cerrados en el período con estos filtros.",
                null);
    }

    private static String caption(int size) {
        return size == 1
                ? "1 expediente"
                : "%d expedientes · ordenados por fecha de resolución".formatted(size);
    }

    private static TableBlock.Row row(ResolutionReportRow row, ZoneId zone) {
        boolean departed = Boolean.FALSE.equals(followed(row));
        DateTimeFormatter stamp = ReportLabels.DATE_TIME.withZone(zone);
        return new TableBlock.Row(List.of(
                new TableBlock.Cell(
                        TableBlock.Part.strong(String.valueOf(row.caseId()), ReportTheme.INK), null),
                TableBlock.Cell.of(row.insuredName(), "DNI " + row.insuredDni()),
                TableBlock.Cell.of(row.claimCause(), row.branch()),
                TableBlock.Cell.of(stamp.format(row.reportedAt()), stamp.format(row.resolvedAt())),
                new TableBlock.Cell(
                        TableBlock.Part.strong(ReportLabels.duration(row.totalMinutes()),
                                ReportTheme.INK),
                        row.waitingMinutes() == 0 ? null
                                : TableBlock.Part.muted(
                                        ReportLabels.duration(row.waitingMinutes()) + " de espera")),
                new TableBlock.Cell(
                        TableBlock.Part.muted(ReportLabels.classification(row.classification())),
                        TableBlock.Part.strong(ReportLabels.decision(row.analystDecision()),
                                departed ? ReportTheme.STATUS_DANGER : ReportTheme.INK)),
                TableBlock.Cell.of(ReportLabels.status(row.finalStatus()),
                        row.analystName() == null ? "sin analista" : row.analystName())),
                departed ? ReportTheme.DANGER_SOFT : null);
    }

    /** What the system suggested against what the analyst decided, counted over the same rows. */
    private static MatrixBlock matrix(List<ResolutionReportRow> rows) {
        boolean anyUndecided = rows.stream().anyMatch(row -> row.analystDecision() == null);
        List<String> columns = anyUndecided
                ? List.of("Aprobó", "Rechazó", "Sin decisión")
                : List.of("Aprobó", "Rechazó");

        Map<Classification, long[]> counts = new LinkedHashMap<>();
        for (ResolutionReportRow row : rows) {
            long[] cells = counts.computeIfAbsent(row.classification(), key -> new long[3]);
            cells[decisionColumn(row.analystDecision())]++;
        }

        List<MatrixBlock.Row> matrixRows = new ArrayList<>();
        for (Map.Entry<Classification, long[]> entry : counts.entrySet()) {
            Classification classification = entry.getKey();
            long[] cells = entry.getValue();
            List<MatrixBlock.Cell> row = new ArrayList<>();
            for (int column = 0; column < columns.size(); column++) {
                row.add(new MatrixBlock.Cell(cells[column], tone(classification, column)));
            }
            matrixRows.add(new MatrixBlock.Row(
                    ReportLabels.classification(classification), row));
        }
        return new MatrixBlock("Sistema y analista, cruzados", columns, matrixRows, MATRIX_LEGEND);
    }

    private static int decisionColumn(String decision) {
        return switch (decision == null ? "" : decision) {
            case "APPROVE", "APROBAR" -> COLUMN_APPROVED;
            case "REJECT", "RECHAZAR" -> COLUMN_REJECTED;
            default -> COLUMN_UNDECIDED;
        };
    }

    /**
     * Only a row carrying an actionable recommendation has an agreeing cell and a departing one.
     * Fast Track, a request for manual review and an undecided case have nothing to agree with, so
     * their counts stay neutral instead of reading as a verdict on the analyst.
     */
    private static MatrixBlock.Tone tone(Classification classification, int column) {
        if (classification == null || column == COLUMN_UNDECIDED) {
            return MatrixBlock.Tone.NEUTRAL;
        }
        return switch (classification) {
            case LLM_RECOMIENDA_APROBAR -> column == COLUMN_APPROVED
                    ? MatrixBlock.Tone.AGREEMENT : MatrixBlock.Tone.DEPARTURE;
            case LLM_NO_RECOMIENDA_APROBAR -> column == COLUMN_REJECTED
                    ? MatrixBlock.Tone.AGREEMENT : MatrixBlock.Tone.DEPARTURE;
            case FAST_TRACK, FALTA_DOCUMENTACION, LLM_SOLICITA_REVISION_MANUAL ->
                    MatrixBlock.Tone.NEUTRAL;
        };
    }

    // ── shared ──────────────────────────────────────────────────────────────────

    private static Boolean followed(ResolutionReportRow row) {
        return Recommendations.followed(row.classification(), row.analystDecision());
    }

    private static List<ResolutionReportRow> decided(List<ResolutionReportRow> rows) {
        return rows.stream()
                .filter(row -> row.finalStatus() == CaseStatus.APPROVED
                        || row.finalStatus() == CaseStatus.REJECTED)
                .toList();
    }

    private static long median(List<Long> sorted) {
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    private static Double share(long count, long total) {
        return total == 0 ? null : (double) count / total;
    }

    private static String status(MetricCount count) {
        return ReportLabels.status(CaseStatus.valueOf(count.label()));
    }

    /** Every status label is an adjective ending in -o, so the plural is the one rule. */
    private static String plural(String label) {
        return label.toLowerCase(Locale.ROOT) + "s";
    }

    private static ReportTheme.Rgb tone(CaseStatus status) {
        return switch (status) {
            case APPROVED -> ReportTheme.STATUS_OK;
            case REJECTED, LAPSED -> ReportTheme.STATUS_DANGER;
            case AWAITING_DOCUMENTATION, CLASSIFICATION_FAILED -> ReportTheme.STATUS_WARNING;
            case PENDING_CLASSIFICATION, PENDING_ANALYST_REVIEW, PENDING_EXPERT_REPORT,
                 PENDING_REPAIR -> ReportTheme.STATUS_INFO;
        };
    }

    private static String period(ResolutionReport report) {
        return format(report.from()) + " — " + format(report.to());
    }

    private static String format(LocalDate date) {
        return ReportLabels.DATE.format(date);
    }
}
