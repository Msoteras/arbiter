package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseSpecifications;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "Mis expedientes" para un asegurado que es cliente de más de una aseguradora: junta sus casos
 * de todos los esquemas a los que pertenece en una sola lista.
 *
 * <p><b>Solo aplica al rol ASEGURADO.</b> Un analista o un referente pertenecen a una aseguradora
 * y ver los expedientes de otra sería una fuga entre tenants — su bandeja sigue el camino normal,
 * acotada por el {@code search_path} de su esquema.
 *
 * <p>Los esquemas sobre los que itera salen de {@code insurerIds}, un claim <b>firmado</b> del
 * JWT, nunca de un parámetro del request: es lo único que garantiza que alguien no pueda nombrar
 * un tenant al que no pertenece. Además cada caso pasa por el filtro de DNI, así que un id de
 * aseguradora de más tampoco alcanzaría para ver expedientes ajenos.
 *
 * <p>Pagina en memoria, lo que sería inaceptable en la bandeja del analista (miles de casos) y
 * acá no lo es: el universo son <b>los siniestros propios de una persona</b>, un puñado. Ordenar
 * bien entre esquemas exige traer de cada uno y recién después cortar; con este volumen es
 * barato y es la única forma de que el orden global sea correcto.
 */
@Service
@RequiredArgsConstructor
public class InsuredCaseAggregator {

    private final CaseRepository caseRepository;
    private final InsurerRepository insurerRepository;

    /**
     * Un expediente junto con la aseguradora de la que salió. Sin esto el origen se pierde al
     * fusionar: los ids son autoincrementales <b>por esquema</b>, así que el mismo número puede
     * existir en las dos aseguradoras y después no hay forma de saber a cuál abrir.
     */
    public record InsuredCase(Case caseRecord, String insurerSlug, String insurerName) {
    }

    /**
     * Devuelve entidades y no {@code CaseResponse} a propósito: aplanar es de
     * {@code CaseServiceImpl}, que es el único lugar donde vive esa forma. Si este servicio
     * mapeara, los dos beans se necesitarían mutuamente.
     */
    public Page<InsuredCase> findOwnCases(CaseStatus status, String claimCause, String policyNumber,
                                    LocalDate eventDateFrom, LocalDate eventDateTo,
                                    String q, RiskBand riskBand, Pageable pageable) {
        CallerContext.Caller caller = CallerContext.get();
        if (caller.insuredId() == null || caller.insurerIds().isEmpty()) {
            // Un asegurado sin DNI o sin aseguradoras en el token no tiene expedientes que ver.
            // Devolver vacío y no "todos" es la diferencia entre un bug y una fuga.
            return Page.empty(pageable);
        }
        // Nota: los expedientes de otra aseguradora vienen sin el análisis joineado, porque
        // llm_analysis/risk_analysis son por esquema y se consultan con el tenant del request ya
        // restaurado. No se nota: el asegurado nunca ve clasificación ni riesgo (ver la memoria
        // de visibilidad asegurado vs analista).

        // El DNI se fuerza como filtro en vez de tomarse del request: es lo que ata el resultado
        // al que pregunta.
        Specification<Case> spec = CaseSpecifications.withFilters(
                status, claimCause, policyNumber, caller.insuredId(),
                eventDateFrom, eventDateTo, q, riskBand);

        String callerTenant = TenantContext.get();
        List<InsuredCase> merged = new ArrayList<>();
        try {
            for (Insurer insurer : insurerRepository.findAllById(caller.insurerIds())) {
                if (!insurer.isActive()) {
                    continue;
                }
                TenantContext.set(insurer.getSchemaName());
                caseRepository.findAll(spec, Sort.unsorted()).forEach(found ->
                        merged.add(new InsuredCase(found, InsurerSlug.of(insurer), insurer.getName())));
            }
        } finally {
            // Restaura el tenant del request: el resto de la request (y la conexión que se
            // devuelve al pool) tiene que seguir viendo el esquema que le corresponde.
            TenantContext.set(callerTenant);
        }

        return page(merged, pageable);
    }

    /**
     * El orden lo impone esta vista y se ignora el {@code Sort} del pageable: ordenar por un campo
     * arbitrario exigiría reimplementar en memoria lo que hace la BD, y el portal del asegurado no
     * ofrece ordenar. Se usa fecha de denuncia descendente, que es lo que espera ver primero.
     *
     * <p>El id va sólo como desempate estable: es autoincremental <b>por esquema</b>, así que se
     * repite entre aseguradoras y por sí solo no ordena nada.
     */
    private Page<InsuredCase> page(List<InsuredCase> merged, Pageable pageable) {
        merged.sort(Comparator.comparing((InsuredCase it) -> it.caseRecord().getReportedAt(),
                        Comparator.reverseOrder())
                .thenComparing(it -> it.caseRecord().getId(), Comparator.reverseOrder()));

        int from = (int) Math.min(pageable.getOffset(), merged.size());
        int to = Math.min(from + pageable.getPageSize(), merged.size());
        return new PageImpl<>(List.copyOf(merged.subList(from, to)), pageable, merged.size());
    }
}
