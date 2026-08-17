package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository.PolicyCoverageOwner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Qué hechos generadores cubre una póliza, para el aviso en rojo del wizard. Se prueba que resuelva
 * la cobertura en el esquema emisor, que solo la dueña pueda preguntar, y que el tenant vuelva a su
 * lugar aunque el camino falle (si no, el resto del request escribiría en la aseguradora equivocada).
 */
@ExtendWith(MockitoExtension.class)
class PolicyCoverageServiceTest {

    private static final String CALLER_TENANT = "arbiter_bbva";
    private static final String ISSUING_TENANT = "arbiter_provincia";
    private static final String POLICY_NUMBER = "POL-CEL-2026-042";
    private static final String OWNER_DNI = "42.987.654";

    @Mock
    private PolicyTenantLocator policyTenantLocator;
    @Mock
    private PolicyRepository policyRepository;
    @Mock
    private RuleServiceClient ruleServiceClient;

    @InjectMocks
    private PolicyCoverageService service;

    @AfterEach
    void clearContext() {
        CallerContext.clear();
        TenantContext.clear();
    }

    private void callerIs(String dni) {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller(dni, List.of(1L, 2L), CALLER_TENANT));
    }

    /** Fila que devuelve {@code findCoverageAndOwner}: coverageId (o null) + DNI del dueño (o null). */
    private PolicyCoverageOwner row(Long coverageId, String ownerDni) {
        return new PolicyCoverageOwner() {
            @Override
            public Long getCoverageId() {
                return coverageId;
            }

            @Override
            public String getOwnerDni() {
                return ownerDni;
            }
        };
    }

    @Test
    void returnsCoveredCauses_resolvingInTheIssuingTenant() {
        callerIs(OWNER_DNI);
        when(policyTenantLocator.locate(POLICY_NUMBER)).thenReturn(ISSUING_TENANT);
        when(policyRepository.findCoverageAndOwner(POLICY_NUMBER))
                .thenReturn(Optional.of(row(5L, OWNER_DNI)));
        when(ruleServiceClient.coveredClaimCauses(5L))
                .thenReturn(List.of("Robo en vía pública"));

        assertThat(service.coveredClaimCauses(POLICY_NUMBER))
                .containsExactly("Robo en vía pública");
        // El sondeo movió el tenant al emisor, pero al terminar vuelve al del que llama.
        assertThat(TenantContext.get()).isEqualTo(CALLER_TENANT);
    }

    @Test
    void policyOfAnotherInsured_throws404WithoutHittingRules() {
        callerIs(OWNER_DNI);
        when(policyTenantLocator.locate(POLICY_NUMBER)).thenReturn(ISSUING_TENANT);
        when(policyRepository.findCoverageAndOwner(POLICY_NUMBER))
                .thenReturn(Optional.of(row(5L, "30.555.777")));

        assertThatThrownBy(() -> service.coveredClaimCauses(POLICY_NUMBER))
                .isInstanceOf(PolicyNotFoundException.class);
        verify(ruleServiceClient, never()).coveredClaimCauses(any());
        assertThat(TenantContext.get()).isEqualTo(CALLER_TENANT);
    }

    @Test
    void policyWithoutCoverage_isEmptyWithoutHittingRules() {
        callerIs(OWNER_DNI);
        when(policyTenantLocator.locate(POLICY_NUMBER)).thenReturn(ISSUING_TENANT);
        when(policyRepository.findCoverageAndOwner(POLICY_NUMBER))
                .thenReturn(Optional.of(row(null, OWNER_DNI)));

        assertThat(service.coveredClaimCauses(POLICY_NUMBER)).isEmpty();
        verify(ruleServiceClient, never()).coveredClaimCauses(any());
    }

    @Test
    void unknownPolicy_throws404() {
        callerIs(OWNER_DNI);
        when(policyTenantLocator.locate(POLICY_NUMBER)).thenReturn(ISSUING_TENANT);
        when(policyRepository.findCoverageAndOwner(POLICY_NUMBER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.coveredClaimCauses(POLICY_NUMBER))
                .isInstanceOf(PolicyNotFoundException.class);
        assertThat(TenantContext.get()).isEqualTo(CALLER_TENANT);
    }
}
