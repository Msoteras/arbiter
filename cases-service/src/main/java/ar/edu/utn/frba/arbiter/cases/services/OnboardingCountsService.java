package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ServiceProviderRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.SettlementAuthorityRepository;
import ar.edu.utn.frba.arbiter.common.dto.OnboardingCaseCounts;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** What cases-service knows about the insurer's setup, for rules-service's checklist. */
@Service
@RequiredArgsConstructor
public class OnboardingCountsService {

    private final BranchRepository branchRepository;
    private final InsurerAdapter insurerAdapter;
    private final ServiceProviderRepository serviceProviderRepository;
    private final SettlementAuthorityRepository settlementAuthorityRepository;

    @Transactional(readOnly = true)
    public OnboardingCaseCounts counts() {
        List<Long> withPolicies = branchRepository.findAll().stream()
                .filter(branch -> !insurerAdapter.findCoverageNames(externalName(branch)).isEmpty())
                .map(Branch::getId)
                .toList();
        return new OnboardingCaseCounts(
                withPolicies,
                (int) serviceProviderRepository.countByActiveTrue(),
                (int) settlementAuthorityRepository.count());
    }

    private static String externalName(Branch branch) {
        return branch.getExternalName() != null ? branch.getExternalName() : branch.getName();
    }
}
