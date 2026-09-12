package ar.edu.utn.frba.arbiter.reports.controllers;

import ar.edu.utn.frba.arbiter.reports.dto.ClaimMetrics;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsRange;
import ar.edu.utn.frba.arbiter.reports.services.ClaimMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The management dashboard: volume, efficiency and outcome of one insurer's claims over a period.
 *
 * <p>There is no insurer parameter, by design. The company is the tenant schema the caller's JWT
 * resolves to, so a referent cannot reach another company's figures even by editing the URL
 * (acceptance criterion 1).
 *
 * <p>Open to the analyst as well as the referent, the same call the frontend's
 * {@code insurer/dashboard} route already makes: these are metrics of the operation, not the
 * insurer's configuration.
 */
@RestController
@RequestMapping("/api/v1/reports/metrics")
@RequiredArgsConstructor
@Tag(name = "Claim metrics", description = "Indicadores de gestión de siniestros de la aseguradora")
public class ClaimMetricsController {

    private static final String REPORT_READERS = "hasAnyRole('REFERENTE_ASEGURADORA', 'ANALISTA_SINIESTROS')";

    private final ClaimMetricsService claimMetricsService;

    @GetMapping
    @PreAuthorize(REPORT_READERS)
    @Operation(summary = "Indicadores de gestión del período",
            description = "KPIs, distribuciones y línea de tiempo de los siniestros de la aseguradora del "
                    + "caller. El período se pide con range (WEEK, MONTH o QUARTER, ventanas de 7, 30 o 90 "
                    + "días terminadas hoy) o con from y to (días calendario, ambos incluidos); los dos "
                    + "juntos son un 400. Sin ninguno, el último mes. Período máximo: 366 días. "
                    + "branchId y analystId recortan a un ramo y al analista ASIGNADO; sin ellos, todos.")
    public ClaimMetrics metrics(
            @RequestParam(required = false) MetricsRange range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long analystId
    ) {
        return claimMetricsService.generate(range, from, to, new MetricsFilter(branchId, analystId));
    }
}
