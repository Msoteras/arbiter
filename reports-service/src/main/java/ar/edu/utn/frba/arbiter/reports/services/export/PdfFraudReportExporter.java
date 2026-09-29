package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSummary;
import ar.edu.utn.frba.arbiter.reports.dto.ReportBranding;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.services.ReportBrandingService;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.BarListBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.Block;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.BulletsBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.CalloutBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ColumnsBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.DefinitionsBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.MetaStripBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.PdfDocumentWriter;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ProseBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ReportDocument;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ReportTheme;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.SectionBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.StatCardsBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.TableBlock;
import ar.edu.utn.frba.arbiter.reports.services.export.pdf.TitleBlock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PdfFraudReportExporter implements FraudReportExporter {

    private static final String TITLE = "Reporte de detección de fraude";
    private static final String KIND = "FRD";

    private static final String SCOPE = "El sistema no determina fraude: señala indicios para "
            + "revisión humana. El score de riesgo es una sugerencia del motor de scoring, no una "
            + "conclusión. «Fraude determinado» solo se marca cuando existe resolución del analista "
            + "con respaldo pericial.";

    private static final String METHOD = "Se evaluaron las denuncias cuya fecha cae dentro del "
            + "período y cuyo ramo entra en el filtro. Cada una pasó por las reglas vigentes al "
            + "momento de la denuncia y queda señalada cuando dispara al menos una.";

    private static final List<DefinitionsBlock.Definition> GLOSSARY = List.of(
            new DefinitionsBlock.Definition("Score de riesgo",
                    "Prioridad de revisión sugerida por el motor. No califica al asegurado."),
            new DefinitionsBlock.Definition("Señales cruzadas",
                    "Denuncia que disparó dos o más reglas distintas."),
            new DefinitionsBlock.Definition("Fraude determinado",
                    "Solo se marca con resolución del analista y respaldo pericial. Con el "
                            + "expediente abierto, «No» no es un descarte."));

    private static final List<TableBlock.Column> COLUMNS = List.of(
            new TableBlock.Column("Nº", 6, false),
            new TableBlock.Column("Asegurado", 20, false),
            new TableBlock.Column("Siniestro", 15, false),
            new TableBlock.Column("Denuncia", 11, false),
            new TableBlock.Column("Score de riesgo", 12, false),
            new TableBlock.Column("Señales", 24, false),
            new TableBlock.Column("Estado", 12, false));

    private final Clock clock;
    private final ReportBrandingService reportBrandingService;

    @Override
    public ReportFormat format() {
        return ReportFormat.PDF;
    }

    @Override
    public byte[] export(FraudReport report) {
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

    private static List<Block> blocks(FraudReport report, ZoneId zone, String code) {
        List<Block> blocks = new ArrayList<>();

        blocks.add(new TitleBlock(TITLE, "Período " + period(report), code));
        blocks.add(new MetaStripBlock(List.of(
                MetaStripBlock.Cell.of("Ramo", ReportLabels.filterValue(report.branch())),
                MetaStripBlock.Cell.of("Score de riesgo", report.riskBand() == null
                        ? "Todos" : ReportLabels.alertLevel(report.riskBand())),
                ReportChrome.requestedBy(),
                ReportChrome.issuedAt(report.generatedAt(), zone))));
        blocks.add(CalloutBlock.scope(SCOPE));

        blocks.add(SectionBlock.of("Resumen del período"));
        blocks.add(new StatCardsBlock(cards(report)));
        blocks.add(ColumnsBlock.evenly(byAlertLevel(report.summary()), bySignal(report.summary())));

        List<BulletsBlock.Bullet> bullets = bullets(report);
        if (!bullets.isEmpty()) {
            blocks.add(new BulletsBlock("Para la lectura del referente", bullets));
        }

        blocks.add(table(report, zone));
        blocks.add(ColumnsBlock.evenly(new ProseBlock("Cómo se produjo este informe", METHOD),
                new DefinitionsBlock("Qué significa cada término", GLOSSARY)));
        blocks.add(ReportChrome.provenance(code, report.generatedAt(), zone));
        blocks.add(ReportChrome.signatures());
        return blocks;
    }

    private static List<StatCardsBlock.Card> cards(FraudReport report) {
        FraudSummary summary = report.summary();
        return List.of(
                StatCardsBlock.Card.of("Denuncias del período",
                        String.valueOf(summary.totalClaims()),
                        "%d en el período anterior".formatted(report.previousSummary().totalClaims())),
                new StatCardsBlock.Card("Con al menos una señal",
                        ReportLabels.percentWithOneDecimal(summary.flaggedRate()),
                        "%d de %d denuncias".formatted(summary.flagged(), summary.totalClaims()),
                        summary.flagged() == 0
                                ? StatCardsBlock.Style.PLAIN : StatCardsBlock.Style.ALERT),
                StatCardsBlock.Card.of("Con dos o más señales",
                        String.valueOf(summary.multiSignal()),
                        summary.multiSignal() == 0
                                ? "ninguna cruzó dos reglas" : "cruzaron más de una regla"),
                fraudCard(report));
    }

    private static StatCardsBlock.Card fraudCard(FraudReport report) {
        FraudSummary summary = report.summary();
        long open = report.rows().stream().filter(row -> open(row.status())).count();
        if (summary.fraudDetermined() == 0 && open > 0) {
            return new StatCardsBlock.Card("Fraude determinado", "Sin determinar",
                    "%d de las %d señaladas siguen abiertas".formatted(open, summary.flagged()),
                    StatCardsBlock.Style.UNAVAILABLE);
        }
        return StatCardsBlock.Card.of("Fraude determinado",
                String.valueOf(summary.fraudDetermined()),
                summary.backedByExpert() == summary.fraudDetermined()
                        ? "todos con respaldo pericial"
                        : "%d con respaldo pericial".formatted(summary.backedByExpert()));
    }

    private static Block byAlertLevel(FraudSummary summary) {
        List<BarListBlock.Bar> bars = summary.byAlertLevel().stream()
                .map(count -> new BarListBlock.Bar(ReportLabels.alertLevel(count.label()),
                        count.count(), share(count.count(), summary.flagged()),
                        alertTone(count.label())))
                .toList();
        return new BarListBlock("Por score de riesgo", bars,
                "Sobre las denuncias señaladas, no sobre el total del período.");
    }

    private static Block bySignal(FraudSummary summary) {
        List<BarListBlock.Bar> bars = summary.bySignal().stream()
                .map(count -> new BarListBlock.Bar(ReportLabels.signal(count.label()), count.count(),
                        share(count.count(), summary.flagged()), ReportTheme.INK_SOFT))
                .toList();
        return new BarListBlock("Por señal disparada", bars, null);
    }

    private static List<BulletsBlock.Bullet> bullets(FraudReport report) {
        List<BulletsBlock.Bullet> bullets = new ArrayList<>();

        repeatInsured(report).ifPresent(entry -> bullets.add(new BulletsBlock.Bullet(
                "%s de las %d denuncias señaladas corresponden al mismo asegurado, %s (DNI %s)."
                        .formatted(entry.getValue(), report.rows().size(), entry.getKey().name(),
                                entry.getKey().dni()),
                ReportTheme.STATUS_RISK)));

        long open = report.rows().stream().filter(row -> open(row.status())).count();
        if (open > 0) {
            bullets.add(new BulletsBlock.Bullet(
                    ("%d de las %d señaladas siguen sin resolver. Mientras tanto, el indicador de "
                            + "fraude determinado no es interpretable.")
                            .formatted(open, report.rows().size()),
                    ReportTheme.STATUS_WARNING));
        }

        long critical = report.rows().stream()
                .filter(row -> row.riskBand() == RiskBand.CRITICAL)
                .count();
        if (critical > 0) {
            bullets.add(new BulletsBlock.Bullet(
                    critical == 1
                            ? "Una denuncia quedó en score crítico y encabeza la lista de revisión."
                            : "%d denuncias quedaron en score crítico y encabezan la lista de revisión."
                                    .formatted(critical),
                    ReportTheme.STATUS_DANGER));
        }
        return bullets;
    }

    private static Optional<Map.Entry<Insured, Long>> repeatInsured(FraudReport report) {
        if (report.rows().size() < 3) {
            return Optional.empty();
        }
        return report.rows().stream()
                .collect(Collectors.groupingBy(
                        row -> new Insured(row.insuredName(), row.insuredDni()),
                        Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(entry -> entry.getValue() >= 2);
    }

    private record Insured(String name, String dni) {}

    private static TableBlock table(FraudReport report, ZoneId zone) {
        List<TableBlock.Row> rows = report.rows().stream().map(row -> row(row, zone)).toList();
        return new TableBlock(
                "Denuncias con señales",
                "%d de %d · ordenadas por cantidad de señales y score de riesgo"
                        .formatted(report.rows().size(), report.summary().totalClaims()),
                COLUMNS,
                rows,
                "Ninguna denuncia del período disparó una señal con estos filtros.");
    }

    private static TableBlock.Row row(FraudReportRow row, ZoneId zone) {
        DateTimeFormatter stamp = ReportLabels.DATE_TIME.withZone(zone);
        return new TableBlock.Row(List.of(
                new TableBlock.Cell(
                        TableBlock.Part.strong(String.valueOf(row.caseId()), ReportTheme.INK), null),
                TableBlock.Cell.of(row.insuredName(), "DNI %s · %d denuncias/12m"
                        .formatted(row.insuredDni(), row.claimsInWindow())),
                TableBlock.Cell.of(row.claimCause(), row.branch()),
                TableBlock.Cell.of(stamp.format(row.reportedAt())),
                new TableBlock.Cell(
                        TableBlock.Part.strong(ReportLabels.alertLevel(row.riskBand()),
                                bandTone(row.riskBand())), null),
                new TableBlock.Cell(TableBlock.Part.muted(ReportLabels.signals(row)), null),
                TableBlock.Cell.of(ReportLabels.status(row.status()),
                        ReportLabels.fraudDetermination(row).equals("No")
                                ? null : "fraude: " + ReportLabels.fraudDetermination(row))),
                row.riskBand() == RiskBand.CRITICAL ? ReportTheme.DANGER_SOFT : null);
    }

    private static boolean open(CaseStatus status) {
        return status != CaseStatus.APPROVED && status != CaseStatus.REJECTED
                && status != CaseStatus.LAPSED;
    }

    private static ReportTheme.Rgb alertTone(String bucket) {
        return switch (bucket) {
            case "CRITICAL" -> ReportTheme.STATUS_DANGER;
            case "HIGH" -> ReportTheme.STATUS_RISK;
            default -> ReportTheme.MUTED_SOFT;
        };
    }

    private static ReportTheme.Rgb bandTone(RiskBand band) {
        if (band == null) {
            return ReportTheme.MUTED;
        }
        return switch (band) {
            case CRITICAL -> ReportTheme.STATUS_DANGER;
            case HIGH -> ReportTheme.STATUS_RISK;
            case MEDIUM -> ReportTheme.STATUS_WARNING;
            case LOW -> ReportTheme.MUTED;
        };
    }

    private static Double share(long count, long total) {
        return total == 0 ? null : (double) count / total;
    }

    private static String period(FraudReport report) {
        return format(report.from()) + " — " + format(report.to());
    }

    private static String format(LocalDate date) {
        return ReportLabels.DATE.format(date);
    }
}
