package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ServiceProviderRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.SettlementAuthorityRepository;
import ar.edu.utn.frba.arbiter.common.dto.OnboardingCaseCounts;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnboardingCountsServiceTest {

    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final InsurerAdapter insurerAdapter = mock(InsurerAdapter.class);
    private final ServiceProviderRepository providerRepository = mock(ServiceProviderRepository.class);
    private final SettlementAuthorityRepository authorityRepository = mock(SettlementAuthorityRepository.class);

    private final OnboardingCountsService service =
            new OnboardingCountsService(branchRepository, insurerAdapter, providerRepository, authorityRepository);

    /** A branch without policies can't receive a claim, so it gets no coverages step. */
    @Test
    void onlyBranchesWithCoveragesOnTheInsurersPoliciesCount() {
        when(branchRepository.findAll()).thenReturn(List.of(
                Branch.builder().id(1L).name("Teléfonos").externalName("Celulares").build(),
                Branch.builder().id(2L).name("Tecnología Portátil").build()));
        when(insurerAdapter.findCoverageNames("Celulares")).thenReturn(List.of("Robo de celular"));
        when(insurerAdapter.findCoverageNames("Tecnología Portátil")).thenReturn(List.of());
        when(providerRepository.countByActiveTrue()).thenReturn(3L);
        when(authorityRepository.count()).thenReturn(1L);

        OnboardingCaseCounts counts = service.counts();

        assertThat(counts.branchIdsWithPolicies()).containsExactly(1L);
        assertThat(counts.activeProviders()).isEqualTo(3);
        assertThat(counts.branchesWithSettlementCap()).isEqualTo(1);
    }
}
