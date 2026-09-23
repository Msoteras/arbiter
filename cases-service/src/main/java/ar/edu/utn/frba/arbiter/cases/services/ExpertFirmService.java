package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.ExpertFirmRequest;
import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.dto.ExpertFirmResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.ExpertFirmInUseException;
import ar.edu.utn.frba.arbiter.cases.exceptions.ExpertFirmNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertFirm;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ExpertAssessmentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ExpertFirmRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * The expert firm catalog the referent manages and the analyst picks from when referring a case.
 * Lives here rather than in rules-service, although edited from the rules screen: it is a provider
 * directory, not an evaluable rule.
 */
@Service
@RequiredArgsConstructor
public class ExpertFirmService {

    private final ExpertFirmRepository expertFirmRepository;
    private final ExpertAssessmentRepository expertAssessmentRepository;
    private final BranchRepository branchRepository;

    /** Inactive ones included: the referent manages the full catalog. */
    @Transactional(readOnly = true)
    public List<ExpertFirmResponse> list() {
        return expertFirmRepository.findAll().stream()
                .sorted(Comparator.comparing(ExpertFirm::getName, String.CASE_INSENSITIVE_ORDER))
                .map(ExpertFirmResponse::from)
                .toList();
    }

    @Transactional
    public ExpertFirmResponse create(ExpertFirmRequest request) {
        ExpertFirm firm = ExpertFirm.builder()
                .name(request.name().trim())
                .email(request.email().trim())
                .zone(blankToNull(request.zone()))
                .branch(resolveBranch(request.branchId()))
                .active(request.active())
                .providerType(typeOf(request))
                .build();
        return ExpertFirmResponse.from(expertFirmRepository.save(firm));
    }

    @Transactional
    public ExpertFirmResponse update(Long id, ExpertFirmRequest request) {
        ExpertFirm firm = expertFirmRepository.findById(id)
                .orElseThrow(() -> new ExpertFirmNotFoundException(id));
        firm.setName(request.name().trim());
        firm.setEmail(request.email().trim());
        firm.setZone(blankToNull(request.zone()));
        firm.setBranch(resolveBranch(request.branchId()));
        firm.setActive(request.active());
        firm.setProviderType(typeOf(request));
        return ExpertFirmResponse.from(expertFirmRepository.save(firm));
    }

    /**
     * Only if never used. A firm with assessments is deactivated instead, which keeps the trail of
     * the referrals it already received.
     */
    @Transactional
    public void delete(Long id) {
        ExpertFirm firm = expertFirmRepository.findById(id)
                .orElseThrow(() -> new ExpertFirmNotFoundException(id));
        if (expertAssessmentRepository.existsByExpertFirm_Id(id)) {
            throw new ExpertFirmInUseException(id);
        }
        expertFirmRepository.delete(firm);
    }

    /** Null means a generalist covering every branch; an unknown id is a 422, not a null. */
    private Branch resolveBranch(Long branchId) {
        if (branchId == null) {
            return null;
        }
        return branchRepository.findById(branchId)
                .orElseThrow(() -> new UnresolvedCaseReferenceException("ramo", String.valueOf(branchId)));
    }

    private static ProviderType typeOf(ExpertFirmRequest request) {
        return request.providerType() == null ? ProviderType.ESTUDIO_LIQUIDADOR : request.providerType();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
