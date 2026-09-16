package ar.edu.utn.frba.arbiter.reports.controllers;

import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.reports.dto.ExportedReport;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReport;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.services.FraudReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The fraud detection report: the claims filed in a period that carry at least one fraud signal.
 *
 * <p>Same readers as the resolution report — the referent reads it, and so does the analyst who has
 * to work the shortlist.
 *
 * <p>The path is {@code /reports/fraud} and not {@code /reportes/fraude}: every endpoint of this
 * module is in English (see {@code /api/v1/reports/resolutions}), and mixing the two spellings in
 * the same API is what the naming convention exists to prevent.
 */
@RestController
@RequestMapping("/api/v1/reports/fraud")
@RequiredArgsConstructor
@Tag(name = "Fraud report", description = "Expedientes con indicios de fraude en un período")
public class FraudReportController {

    private static final String REPORT_READERS = "hasAnyRole('REFERENTE_ASEGURADORA', 'ANALISTA_SINIESTROS')";

    private final FraudReportService fraudReportService;

    @GetMapping
    @PreAuthorize(REPORT_READERS)
    @Operation(summary = "Expedientes con indicios de fraude en un período",
            description = "Denuncias de la aseguradora del caller registradas entre from y to (ambos "
                    + "incluidos, días calendario) sobre las que se disparó al menos una señal: score "
                    + "de riesgo ALTO o CRÍTICO, más de una denuncia del mismo asegurado en los 12 "
                    + "meses previos, o incoherencias del análisis forense de imágenes. Cada fila trae "
                    + "el nivel de alerta (la banda de riesgo del motor), qué señales se cruzaron y si "
                    + "un analista determinó fraude. branchId filtra por ramo y riskBand por nivel de "
                    + "alerta; sin ellos, todos. Período máximo: 366 días. "
                    + "El sistema no determina fraude: señala indicios para revisión humana.")
    public FraudReport report(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) RiskBand riskBand
    ) {
        return fraudReportService.generate(from, to, branchId, riskBand);
    }

    @GetMapping("/export")
    @PreAuthorize(REPORT_READERS)
    @Operation(summary = "Exporta el reporte de detección de fraude",
            description = "Mismos filtros que GET /reports/fraud, como archivo descargable (CSV o PDF).")
    public ResponseEntity<byte[]> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) RiskBand riskBand,
            @RequestParam ReportFormat format
    ) {
        ExportedReport file = fraudReportService.export(from, to, branchId, riskBand, format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.format().mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.content());
    }
}
