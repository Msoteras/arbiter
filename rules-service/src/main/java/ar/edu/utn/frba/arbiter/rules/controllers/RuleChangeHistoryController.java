package ar.edu.utn.frba.arbiter.rules.controllers;

import ar.edu.utn.frba.arbiter.rules.dto.RuleChangeEntry;
import ar.edu.utn.frba.arbiter.rules.services.RuleChangeHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Referente-facing, read-only: the history of changes to the insurer's rules.
 *
 * <p>Only the referente reads it. The analyst doesn't configure rules and doesn't audit the person
 * who does; the insured has nothing to do with either. That matches how the architecture document
 * words the requirement — the record is "consultable por el referente de la aseguradora".
 *
 * <p>There is no write endpoint here and there never should be: the two tables behind this are
 * append-only, written by the rule services as a side effect of each save. An endpoint that could
 * edit or delete an entry would defeat the whole point of keeping them.
 *
 * <p>The tenant schema comes from the JWT like everywhere else in this module, so a referente only
 * ever sees their own insurer's trail.
 */
@RestController
@RequestMapping("/api/v1/rules/history")
@RequiredArgsConstructor
@Tag(name = "Historial de reglas",
        description = "Auditoría de los cambios de configuración de la aseguradora (solo lectura)")
public class RuleChangeHistoryController {

    private final RuleChangeHistoryService service;

    @GetMapping
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Historial de cambios de reglas",
            description = "Feed cronológico (más reciente primero) de cada cambio de configuración, con el "
                    + "detalle campo por campo de qué pasó de qué valor a cuál. Une las dos tablas de "
                    + "auditoría (reglas y scoring). El orden es fijo: el parámetro `sort` se ignora, un "
                    + "historial solo se lee desde lo último que pasó.")
    public Page<RuleChangeEntry> find(
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.find(ruleType, branchId, startOfDay(from), startOfNextDay(to), pageable);
    }

    @GetMapping("/rule-types")
    @PreAuthorize("hasRole('REFERENTE_ASEGURADORA')")
    @Operation(summary = "Tipos de regla presentes en el historial",
            description = "Solo los que la aseguradora efectivamente editó alguna vez — el filtro no ofrece "
                    + "opciones que devolverían una página vacía.")
    public List<String> ruleTypes() {
        return service.ruleTypes();
    }

    /**
     * The filter is a date and the column is an instant. UTC and not the browser's zone: the whole
     * platform stores {@code TIMESTAMPTZ} and every other date filter in the API reads the same way
     * — a per-request zone would make two users disagree about which day a change belongs to.
     */
    private static Instant startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** {@code to} is inclusive for the referente, so the bound is the start of the following day. */
    private static Instant startOfNextDay(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
