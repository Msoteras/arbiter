package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.ServiceProviderRequest;
import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.dto.ServiceProviderResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.ServiceProviderInUseException;
import ar.edu.utn.frba.arbiter.cases.exceptions.ServiceProviderNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.ServiceProvider;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseReferralRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ServiceProviderRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The service provider catalog the referent manages and the analyst picks from when referring a case.
 * Lives here rather than in rules-service, although edited from the rules screen: it is a provider
 * directory, not an evaluable rule.
 */
@Service
@RequiredArgsConstructor
public class ServiceProviderService {

    private final ServiceProviderRepository serviceProviderRepository;
    private final CaseReferralRepository caseReferralRepository;
    private final BranchRepository branchRepository;

    /** Inactive ones included: the referent manages the full catalog. */
    @Transactional(readOnly = true)
    public List<ServiceProviderResponse> list() {
        return serviceProviderRepository.findAll().stream()
                .sorted(Comparator.comparing(ServiceProvider::getName, String.CASE_INSENSITIVE_ORDER))
                .map(ServiceProviderResponse::from)
                .toList();
    }

    @Transactional
    public ServiceProviderResponse create(ServiceProviderRequest request) {
        ServiceProvider provider = ServiceProvider.builder()
                .name(request.name().trim())
                .email(request.email().trim())
                .zone(blankToNull(request.zone()))
                .branches(resolveBranches(request.branchIds()))
                .active(request.active())
                .providerType(typeOf(request))
                .build();
        return ServiceProviderResponse.from(serviceProviderRepository.save(provider));
    }

    @Transactional
    public ServiceProviderResponse update(Long id, ServiceProviderRequest request) {
        ServiceProvider provider = serviceProviderRepository.findById(id)
                .orElseThrow(() -> new ServiceProviderNotFoundException(id));
        provider.setName(request.name().trim());
        provider.setEmail(request.email().trim());
        provider.setZone(blankToNull(request.zone()));
        provider.setBranches(resolveBranches(request.branchIds()));
        provider.setActive(request.active());
        provider.setProviderType(typeOf(request));
        return ServiceProviderResponse.from(serviceProviderRepository.save(provider));
    }

    /**
     * Only if never used. A provider with referrals is deactivated instead, which keeps the trail of
     * the referrals it already received.
     */
    @Transactional
    public void delete(Long id) {
        ServiceProvider provider = serviceProviderRepository.findById(id)
                .orElseThrow(() -> new ServiceProviderNotFoundException(id));
        if (caseReferralRepository.existsByProvider_Id(id)) {
            throw new ServiceProviderInUseException(id);
        }
        serviceProviderRepository.delete(provider);
    }

    /** Empty means a generalist covering every branch; any unknown id is a 422, not a skipped one. */
    private Set<Branch> resolveBranches(List<Long> branchIds) {
        if (branchIds == null) {
            return new HashSet<>();
        }
        Set<Branch> branches = new HashSet<>();
        for (Long branchId : new LinkedHashSet<>(branchIds)) {
            branches.add(branchRepository.findById(branchId)
                    .orElseThrow(() -> new UnresolvedCaseReferenceException("ramo", String.valueOf(branchId))));
        }
        return branches;
    }

    private static ProviderType typeOf(ServiceProviderRequest request) {
        return request.providerType() == null ? ProviderType.ESTUDIO_LIQUIDADOR : request.providerType();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
