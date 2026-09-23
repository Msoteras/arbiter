package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * System-to-system read of the document schedule for the classification engine. The schedule is
 * keyed by branch + claim cause ids, but the engine only has a {@code coverageId} and the claim
 * cause's name, so both are resolved here first.
 */
@Service
@RequiredArgsConstructor
public class InternalDocumentRequirementService {

    private final CoverageRepository coverageRepository;
    private final DocumentRequirementService documentRequirements;

    /**
     * The configured schedule, or {@code null} when it couldn't be resolved (unknown coverage, or no
     * such claim cause in the branch). An <b>empty list</b> means "needs no documents"; only
     * {@code null} lets the engine fall back to its baseline.
     */
    @Transactional(readOnly = true)
    public List<String> getByCoverage(Long coverageId, String claimCauseName) {
        Long branchId = coverageRepository.findById(coverageId)
                .map(Coverage::getBranchId)
                .orElse(null);
        if (branchId == null) {
            return null;
        }
        return documentRequirements.findByBranchIdAndClaimCauseName(branchId, claimCauseName)
                .orElse(null);
    }
}
