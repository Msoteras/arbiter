package ar.edu.utn.frba.arbiter.reports.controllers;

import ar.edu.utn.frba.arbiter.reports.dto.ExportedReport;
import ar.edu.utn.frba.arbiter.reports.dto.ReportFormat;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReport;
import ar.edu.utn.frba.arbiter.reports.services.ResolutionReportService;
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
 * The resolution report: the JSON the screen previews, and the same data as a downloadable file.
 *
 * <p>The architecture document presents reports to the referent; the analyst reads them too, same
 * as the frontend's {@code insurer/reports} route decides — they're metrics of the operation, not
 * the insurer's configuration.
 */
@RestController
@RequestMapping("/api/v1/reports/resolutions")
@RequiredArgsConstructor
@Tag(name = "Resolution report", description = "Siniestros resueltos en un período")
public class ResolutionReportController {

    private static final String REPORT_READERS = "hasAnyRole('REFERENTE_ASEGURADORA', 'ANALISTA_SINIESTROS')";

    private final ResolutionReportService resolutionReportService;

    @GetMapping
    @PreAuthorize(REPORT_READERS)
    @Operation(summary = "Siniestros resueltos en un período",
            description = "Expedientes de la aseguradora del caller que llegaron a un estado final entre "
                    + "from y to (ambos incluidos, días calendario), con tiempos, clasificación y decisión. "
                    + "claimCause filtra por hecho generador; sin él, todos. Período máximo: 366 días.")
    public ResolutionReport preview(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String claimCause
    ) {
        return resolutionReportService.generate(from, to, claimCause);
    }

    @GetMapping("/export")
    @PreAuthorize(REPORT_READERS)
    @Operation(summary = "Exporta el reporte de resolución",
            description = "Mismos filtros que GET /reports/resolutions, como archivo descargable (CSV o PDF).")
    public ResponseEntity<byte[]> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String claimCause,
            @RequestParam ReportFormat format
    ) {
        ExportedReport file = resolutionReportService.export(from, to, claimCause, format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.format().mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.content());
    }
}
