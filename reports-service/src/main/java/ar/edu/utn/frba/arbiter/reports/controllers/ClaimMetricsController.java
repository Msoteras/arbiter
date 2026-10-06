package ar.edu.utn.frba.arbiter.reports.controllers;

import ar.edu.utn.frba.arbiter.reports.dto.ClaimMetrics;
import ar.edu.utn.frba.arbiter.reports.dto.ComparisonMode;
import ar.edu.utn.frba.arbiter.reports.dto.ComparisonRequest;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsRange;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsRecalculation;
import ar.edu.utn.frba.arbiter.reports.services.ClaimMetricsService;
import ar.edu.utn.frba.arbiter.reports.services.DailyMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The management dashboard. There is no insurer parameter by design: the company is the tenant the
 * caller's JWT resolves to, so another insurer's figures are unreachable even by editing the URL.
 */
@RestController
@RequestMapping("/api/v1/reports/metrics")
@RequiredArgsConstructor
@Tag(name = "Claim metrics", description = "Indicadores de gestión de siniestros de la aseguradora")
public class ClaimMetricsController {

    private static final String REPORT_READERS = "hasAnyRole('REFERENTE_ASEGURADORA', 'ANALISTA_SINIESTROS')";

    private final ClaimMetricsService claimMetricsService;
    private final DailyMetricsService dailyMetricsService;

    @GetMapping
    @PreAuthorize(REPORT_READERS)
    @Operation(summary = "Indicadores de gestión del período",
            description = "KPIs, distribuciones y línea de tiempo de los siniestros de la aseguradora del "
                    + "caller. El período se pide con range (WEEK, MONTH o QUARTER, ventanas de 7, 30 o 90 "
                    + "días terminadas hoy) o con from y to (días calendario, ambos incluidos); los dos "
                    + "juntos son un 400. Sin ninguno, el último mes. Período máximo: 366 días. "
                    + "branchId y analystId recortan a un ramo y al analista ASIGNADO; sin ellos, todos. "
                    + "compare elige contra qué se comparan los KPIs: PREVIOUS_PERIOD (el tramo de igual "
                    + "duración inmediatamente anterior, el default), SAME_PERIOD_LAST_YEAR (las mismas "
                    + "fechas un año antes) o CUSTOM (el rango de compareFrom y compareTo). Un período ya "
                    + "cerrado se responde desde los totales diarios guardados y no cambia solo; el que "
                    + "incluye hoy se calcula siempre en vivo.")
    public ClaimMetrics metrics(
            @RequestParam(required = false) MetricsRange range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long analystId,
            @RequestParam(required = false) ComparisonMode compare,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareTo
    ) {
        return claimMetricsService.generate(range, from, to, new MetricsFilter(branchId, analystId),
                new ComparisonRequest(compare, compareFrom, compareTo));
    }

    @PostMapping("/recalculations")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Recalcula los totales guardados de días ya cerrados",
            description = "Vuelve a calcular, desde los expedientes, los totales diarios de from a to "
                    + "(ambos incluidos, anteriores a hoy). Es la única forma de que cambie un número de "
                    + "un período cerrado: sirve cuando se corrigió un expediente viejo. Responde cuántos "
                    + "días quedaron guardados; los que todavía tienen algo en curso (una denuncia sin "
                    + "clasificar, una liquidación esperando autorización) siguen calculándose en vivo.")
    public MetricsRecalculation recalculate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return dailyMetricsService.recalculate(from, to);
    }
}
