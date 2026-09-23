package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
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
 * Which schema a claim is filed in. The token's {@code tenantSchema} is fixed at login, before the
 * policy is known, so for someone insured by two insurers the claim must follow the policy number,
 * while still limiting the search to the insurers in the signed claim.
 */
@ExtendWith(MockitoExtension.class)
class PolicyTenantLocatorTest {

    private static final String CALLER_TENANT = "arbiter_bbva";
    private static final String POLICY_NUMBER = "POL-CEL-2026-042";

    @Mock
    private InsurerRepository insurerRepository;

    @Mock
    private PolicyRepository policyRepository;

    @Mock
    private InsurerAdapter insurerAdapter;

    @InjectMocks
    private PolicyTenantLocator locator;

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

    private Policy policy() {
        return CaseFixtures.policy(POLICY_NUMBER, "Celular Protegido Premium");
    }

    @Test
    void findsThePolicyInTheSecondInsurer() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", true),
                insurer(2L, "arbiter_provincia", true)));
        // The insurer adapter doesn't have it or doesn't answer: falls back to the local snapshot.
        when(policyRepository.findByExternalPolicyNumber(POLICY_NUMBER))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(policy()));

        assertThat(locator.locate(POLICY_NUMBER)).isEqualTo("arbiter_provincia");
    }

    /**
     * The insurer (source of truth) is asked first, and its answer wins over a stale local snapshot
     * of the same policy in another insurer's schema.
     */
    @Test
    void trustsTheInsurerOverAStaleLocalSnapshotInTheWrongSchema() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("30.555.777", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", true),
                insurer(2L, "arbiter_provincia", true)));
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.of(
                ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse.builder()
                        .policyNumber(POLICY_NUMBER)
                        .insurerId("2")
                        .build()));

        assertThat(locator.locate(POLICY_NUMBER)).isEqualTo("arbiter_provincia");
        verify(policyRepository, never()).findByExternalPolicyNumber(any());
    }

    @Test
    void unknownPolicyInEveryInsurer_fails422() {
        // Neither synced nor in the insurer DB: only then does the number name nothing.
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.empty());
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", true),
                insurer(2L, "arbiter_provincia", true)));
        when(policyRepository.findByExternalPolicyNumber(POLICY_NUMBER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> locator.locate(POLICY_NUMBER))
                .isInstanceOf(UnresolvedCaseReferenceException.class);
    }

    /**
     * Probing schemas must not leave the tenant moved: the caller decides whether to change it.
     * Otherwise the rest of the request writes to the wrong insurer.
     */
    @Test
    void restoresCallerTenantAfterProbing() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", true),
                insurer(2L, "arbiter_provincia", true)));
        when(policyRepository.findByExternalPolicyNumber(POLICY_NUMBER))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(policy()));

        locator.locate(POLICY_NUMBER);

        assertThat(TenantContext.get()).isEqualTo(CALLER_TENANT);
    }

    @Test
    void restoresCallerTenantWhenNothingResolves() {
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.empty());
        TenantContext.set(CALLER_TENANT);
        // Two insurers on purpose: with one there's nothing to probe (see
        // singleInsurer_skipsResolutionEntirely) and the local snapshot fallback wouldn't run.
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", true),
                insurer(2L, "arbiter_provincia", true)));
        when(policyRepository.findByExternalPolicyNumber(POLICY_NUMBER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> locator.locate(POLICY_NUMBER))
                .isInstanceOf(UnresolvedCaseReferenceException.class);

        assertThat(TenantContext.get()).isEqualTo(CALLER_TENANT);
    }

    @Test
    void skipsInactiveInsurers() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L, 2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                insurer(1L, "arbiter_bbva", false),
                insurer(2L, "arbiter_provincia", true)));

        // Only one active insurer is left after filtering: no ambiguity, so it's returned without
        // asking the insurer or the local snapshot.
        assertThat(locator.locate(POLICY_NUMBER)).isEqualTo("arbiter_provincia");
        verify(policyRepository, never()).findByExternalPolicyNumber(any());
    }

    @Test
    void singleInsurer_skipsResolutionEntirely() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(2L), CALLER_TENANT));
        when(insurerRepository.findAllById(List.of(2L)))
                .thenReturn(List.of(insurer(2L, "arbiter_provincia", true)));

        assertThat(locator.locate(POLICY_NUMBER)).isEqualTo("arbiter_provincia");
        verify(insurerAdapter, never()).findPolicy(any());
        verify(policyRepository, never()).findByExternalPolicyNumber(any());
    }

    /** Without the claim (old token, or a call with no user behind it) the resolved tenant is used. */
    @Test
    void withoutInsurerIdsClaim_fallsBackToTheCurrentTenant() {
        TenantContext.set(CALLER_TENANT);
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(), CALLER_TENANT));

        assertThat(locator.locate(POLICY_NUMBER)).isEqualTo(CALLER_TENANT);
        verify(insurerRepository, never()).findAllById(any());
    }
}
