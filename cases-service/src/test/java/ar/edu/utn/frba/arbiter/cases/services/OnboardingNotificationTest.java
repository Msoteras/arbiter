package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.CoverageUpsertRequest;
import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.dto.ServiceProviderRequest;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementAuthorityUpsertRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.ServiceProvider;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseReferralRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ServiceProviderRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.SettlementAuthorityRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every save of a checklist step cases-service owns is reported to rules-service: after a restart,
 * that save is what completes the step, and one that isn't reported leaves it pending for good.
 */
class OnboardingNotificationTest {

    private final OnboardingNotifier notifier = mock(OnboardingNotifier.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);

    @Test
    void everyCoverageSaveReportsItsBranch() {
        CoverageRepository coverageRepository = mock(CoverageRepository.class);
        CoverageService service = new CoverageService(coverageRepository, branchRepository, mock(InsurerAdapter.class), notifier);
        Coverage stored = Coverage.builder().id(5L).branchId(2L).build();
        when(coverageRepository.save(any(Coverage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(coverageRepository.findById(5L)).thenReturn(Optional.of(stored));

        service.create(2L, coverageRequest());
        service.update(5L, coverageRequest());
        service.delete(5L);

        verify(notifier, times(3)).saved(OnboardingStep.COVERAGES, 2L);
    }

    @Test
    void everyProviderSaveIsReported() {
        ServiceProviderRepository providerRepository = mock(ServiceProviderRepository.class);
        CaseReferralRepository referralRepository = mock(CaseReferralRepository.class);
        ServiceProviderService service = new ServiceProviderService(providerRepository, referralRepository, branchRepository, notifier);
        when(branchRepository.findById(1L)).thenReturn(Optional.of(Branch.builder().id(1L).name("Celulares").build()));
        when(providerRepository.save(any(ServiceProvider.class))).thenAnswer(inv -> {
            ServiceProvider provider = inv.getArgument(0);
            provider.setId(9L);
            return provider;
        });
        ServiceProvider existing = ServiceProvider.builder().id(9L).name("Perito").email("p@test.com").build();
        when(providerRepository.findById(9L)).thenReturn(Optional.of(existing));
        ServiceProviderRequest request = new ServiceProviderRequest(
                "Perito", "p@test.com", null, List.of(1L), true, ProviderType.ESTUDIO_LIQUIDADOR);

        service.create(request);
        service.update(9L, request);
        service.delete(9L);

        verify(notifier, times(3)).saved(OnboardingStep.PROVIDERS, null);
    }

    @Test
    void aSettlementCapSaveIsReported() {
        SettlementAuthorityRepository authorityRepository = mock(SettlementAuthorityRepository.class);
        SettlementAuthorityService service = new SettlementAuthorityService(
                authorityRepository, branchRepository, mock(UserRepository.class), notifier);
        when(authorityRepository.findByBranchId(1L)).thenReturn(Optional.empty());

        service.set(1L, new SettlementAuthorityUpsertRequest(new BigDecimal("500000")));

        verify(notifier).saved(OnboardingStep.AUTHORITIES, null);
    }

    private static CoverageUpsertRequest coverageRequest() {
        return new CoverageUpsertRequest("Robo de celular", null, null, null, null, null, null, null,
                null, null, null, false, false, List.of());
    }
}
