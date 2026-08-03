package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Mis expedientes" cuando el asegurado es cliente de más de una aseguradora. Lo que se prueba es
 * el aislamiento entre tenants: que los esquemas recorridos salgan del claim firmado y no de otro
 * lado, y que el tenant del request quede siempre restaurado — si no, la conexión vuelve al pool
 * viendo el esquema equivocado y se lo lleva puesto el próximo request.
 */
@ExtendWith(MockitoExtension.class)
class InsuredCaseAggregatorTest {

    private static final String CALLER_TENANT = "arbiter_bbva";

    @Mock
    private CaseRepository caseRepository;

    @Mock
    private InsurerRepository insurerRepository;

    @InjectMocks
    private InsuredCaseAggregator aggregator;

    @AfterEach
    void clearContext() {
        CallerContext.clear();
        TenantContext.clear();
    }

    private Insurer insurer(Long id, String schema, boolean active) {
        Insurer insurer = new Insurer();
        insurer.setId(id);
        insurer.setSchemaName(schema);
        insurer.setActive(active);
        return insurer;
    }

    private Case caseReportedAt(Long id, Instant reportedAt) {
        Case caseRecord = new Case();
        caseRecord.setId(id);
        caseRecord.setReportedAt(reportedAt);
        caseRecord.setInsured(CaseFixtures.insured("42.987.654", "Martina", "Soteras"));
        return caseRecord;
    }

    private Page<Case> findOwnCases() {
        return aggregator.findOwnCases(null, null, null, null, null, null, null,
                PageRequest.of(0, 10));
    }

    @Test
    void mergesCasesAcrossEveryInsurerTheCallerBelongsTo() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", true),
                insurer(2L, "arbiter_provincia", true)));
        // Cada esquema devuelve lo suyo: el id 1 se repite a propósito, es autoincremental POR
        // esquema, así que colisiona entre aseguradoras y no sirve para ordenar.
        when(caseRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(caseReportedAt(1L, Instant.parse("2026-06-01T10:00:00Z"))))
                .thenReturn(List.of(caseReportedAt(1L, Instant.parse("2026-07-01T10:00:00Z"))));

        Page<Case> result = findOwnCases();

        assertThat(result.getTotalElements()).isEqualTo(2);
        // Más reciente primero: es lo que el portal del asegurado espera ver arriba.
        assertThat(result.getContent().get(0).getReportedAt())
                .isEqualTo(Instant.parse("2026-07-01T10:00:00Z"));
    }

    @Test
    void restoresCallerTenantAfterSweepingOtherSchemas() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", true),
                insurer(2L, "arbiter_provincia", true)));
        when(caseRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());

        findOwnCases();

        assertThat(TenantContext.get()).isEqualTo(CALLER_TENANT);
    }

    @Test
    void restoresCallerTenantEvenWhenAQueryBlowsUp() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L)))
                .thenReturn(List.of(insurer(1L, "arbiter_provincia", true)));
        when(caseRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenThrow(new RuntimeException("db down"));

        try {
            findOwnCases();
        } catch (RuntimeException expected) {
            // El punto del test es el finally, no la excepción.
        }

        assertThat(TenantContext.get()).isEqualTo(CALLER_TENANT);
    }

    @Test
    void skipsInactiveInsurers() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", true),
                insurer(2L, "arbiter_provincia", false)));
        when(caseRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(caseReportedAt(1L, Instant.parse("2026-06-01T10:00:00Z"))));

        Page<Case> result = findOwnCases();

        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    /**
     * Sin DNI en el token no hay con qué atar el resultado a quien pregunta. Devolver vacío y no
     * "todos" es, textualmente, la diferencia entre un bug y una fuga.
     */
    @Test
    void callerWithoutDni_getsNothingRatherThanEverything() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller(null, List.of(1L), CALLER_TENANT));

        assertThat(findOwnCases().getTotalElements()).isZero();
        verify(caseRepository, never()).findAll(any(Specification.class), any(Sort.class));
    }

    @Test
    void callerWithoutInsurerIds_getsNothing() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(), CALLER_TENANT));

        assertThat(findOwnCases().getTotalElements()).isZero();
        verify(insurerRepository, never()).findAllById(any());
    }

    /**
     * La regla más importante del handoff: los esquemas salen del claim firmado. Este test la fija
     * como contrato — si alguien agrega un parámetro de aseguradora al request y lo usa acá, esto
     * tiene que romper.
     */
    @Test
    void onlySweepsSchemasFromTheSignedClaim() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L)))
                .thenReturn(List.of(insurer(1L, "arbiter_bbva", true)));
        List<String> schemasVisited = new ArrayList<>();
        when(caseRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenAnswer(invocation -> {
                    schemasVisited.add(TenantContext.get());
                    return List.of();
                });

        findOwnCases();

        assertThat(schemasVisited).containsExactly("arbiter_bbva");
        verify(insurerRepository).findAllById(List.of(1L));
    }
}
