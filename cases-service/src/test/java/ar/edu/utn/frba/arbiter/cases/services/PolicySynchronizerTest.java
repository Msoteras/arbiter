package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * On-demand sync of a policy's local snapshot (decision #10). What's being tested is that a
 * policy the company has and Arbiter doesn't gets copied instead of rejected — and that what does
 * fail is the coverage, which is referente configuration and can't be invented.
 */
class PolicySynchronizerTest {

    private static final String POLICY_NUMBER = "POL-TEC-2026-050";

    private final InsurerAdapter insurerAdapter = mock(InsurerAdapter.class);
    private final PolicyRepository policyRepository = mock(PolicyRepository.class);
    private final CoverageRepository coverageRepository = mock(CoverageRepository.class);

    private final PolicySynchronizer synchronizer =
            new PolicySynchronizer(insurerAdapter, policyRepository, coverageRepository);

    @Test
    void importsThePolicyFromTheInsurerDatabase() {
        givenRemotePolicy(LocalDate.now().minusMonths(6), LocalDate.now().plusMonths(6), "Daño accidental");
        Coverage coverage = coverage(3L, "Daño accidental");
        when(coverageRepository.findByName("Daño accidental")).thenReturn(Optional.of(coverage));
        when(policyRepository.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));

        Policy saved = synchronizer.importFromInsurer(POLICY_NUMBER, 1L);

        assertThat(saved.getExternalPolicyNumber()).isEqualTo(POLICY_NUMBER);
        assertThat(saved.getInsuredId()).isEqualTo(1L);
        assertThat(saved.getCoverage()).isSameAs(coverage);
        assertThat(saved.getSumInsured()).isEqualByComparingTo("90000");
        assertThat(saved.isInForce()).isTrue();
        assertThat(saved.getSyncedAt()).isNotNull();
    }

    /** The snapshot's coverage window is "today", not the event date: that's the intake gate's job. */
    @Test
    void anExpiredPolicyIsImportedAsNotInForce() {
        givenRemotePolicy(LocalDate.now().minusYears(2), LocalDate.now().minusDays(1), "Robo de celular");
        when(coverageRepository.findByName("Robo de celular")).thenReturn(Optional.of(coverage(1L, "Robo de celular")));
        when(policyRepository.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(synchronizer.importFromInsurer(POLICY_NUMBER, 1L).isInForce()).isFalse();
    }

    @Test
    void aPolicyTheInsurerDoesNotHaveEither_throws() {
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> synchronizer.importFromInsurer(POLICY_NUMBER, 1L))
                .isInstanceOf(UnresolvedCaseReferenceException.class)
                .hasMessageContaining("policy");
    }

    /**
     * The coverage is configured by the referente (deadlines, waiting period, events cap) and only
     * name and amount come from the company: creating one here would leave a coverage with no rule
     * at all. The error names the coverage and not the policy, which is what's really missing.
     */
    @Test
    void aCoverageTheTenantHasNotConfigured_throwsNamingTheCoverage() {
        givenRemotePolicy(LocalDate.now().minusMonths(6), LocalDate.now().plusMonths(6), "Daño accidental");
        when(coverageRepository.findByName("Daño accidental")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> synchronizer.importFromInsurer(POLICY_NUMBER, 1L))
                .isInstanceOf(UnresolvedCaseReferenceException.class)
                .hasMessageContaining("Daño accidental");
    }

    private void givenRemotePolicy(LocalDate from, LocalDate to, String coverageName) {
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.of(PolicyResponse.builder()
                .policyNumber(POLICY_NUMBER)
                .product("Seguro de Tecnología Portátil")
                .insuredAmount(new BigDecimal("90000"))
                .effectiveFrom(from == null ? null : from.atStartOfDay())
                .effectiveTo(to == null ? null : to.atStartOfDay())
                .coverages(List.of(PolicyResponse.Coverage.builder()
                        .code("COB-1")
                        .description(coverageName)
                        .insuredAmount(new BigDecimal("90000"))
                        .build()))
                .build()));
    }

    private Coverage coverage(Long id, String name) {
        Coverage coverage = new Coverage();
        coverage.setId(id);
        coverage.setName(name);
        return coverage;
    }
}
