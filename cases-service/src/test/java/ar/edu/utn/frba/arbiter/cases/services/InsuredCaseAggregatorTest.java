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
 * "My cases" for an insured who is a customer of several insurers. Tests tenant isolation: schemas
 * come only from the signed claim, and the request tenant is always restored, otherwise the
 * connection goes back to the pool on the wrong schema and the next request inherits it.
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

    private Page<InsuredCaseAggregator.InsuredCase> findOwnCases() {
        return aggregator.findOwnCases(null, null, null, null, null, null, null, null, null,
                PageRequest.of(0, 10));
    }

    @Test
    void mergesCasesAcrossEveryInsurerTheCallerBelongsTo() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", true),
                insurer(2L, "arbiter_provincia", true)));
        // Id 1 repeats on purpose: ids are sequences per schema, so they collide across insurers
        // and can't be used for ordering.
        when(caseRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(caseReportedAt(1L, Instant.parse("2026-06-01T10:00:00Z"))))
                .thenReturn(List.of(caseReportedAt(1L, Instant.parse("2026-07-01T10:00:00Z"))));

        Page<InsuredCaseAggregator.InsuredCase> result = findOwnCases();

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent().get(0).caseRecord().getReportedAt())
                .isEqualTo(Instant.parse("2026-07-01T10:00:00Z"));
        // Each one carries its insurer: with colliding ids, that's the only way to reopen the right
        // one.
        assertThat(result.getContent()).extracting(InsuredCaseAggregator.InsuredCase::insurerSlug)
                .containsExactly("provincia", "bbva");
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
            // The point of the test is the finally, not the exception.
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

        Page<InsuredCaseAggregator.InsuredCase> result = findOwnCases();

        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    /**
     * Without a DNI there's nothing to bind the result to the caller. Returning nothing rather than
     * everything is the difference between a bug and a leak.
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
     * Schemas come from the signed claim only: if someone adds an insurer parameter to the request
     * and uses it here, this must break.
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

    /** Filtering by insurer means not visiting the other schema, not discarding its rows afterwards. */
    @Test
    void insurerFilterNarrowsTheSchemasVisited() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(2L)))
                .thenReturn(List.of(insurer(2L, "arbiter_provincia", true)));
        List<String> schemasVisited = new ArrayList<>();
        when(caseRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenAnswer(invocation -> {
                    schemasVisited.add(TenantContext.get());
                    return List.of();
                });

        aggregator.findOwnCases(null, null, null, null, null, null, null, null, 2L,
                PageRequest.of(0, 10));

        assertThat(schemasVisited).containsExactly("arbiter_provincia");
    }

    @Test
    void insurerFilterOutsideTheCallersInsurersReturnsEmpty() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L), CALLER_TENANT));

        Page<InsuredCaseAggregator.InsuredCase> result = aggregator.findOwnCases(
                null, null, null, null, null, null, null, null, 99L, PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isZero();
        verify(insurerRepository, never()).findAllById(any());
    }
}
