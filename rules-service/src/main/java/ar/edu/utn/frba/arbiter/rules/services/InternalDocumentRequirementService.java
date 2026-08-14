package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * System-to-system read of the document schedule for the classification engine.
 *
 * <p>Same key asymmetry as {@link InternalRuleTextService}: the referente configures the schedule
 * <b>by branch</b>, but the claim reaching the engine only carries a {@code coverageId} (branch and
 * claim cause arrive as names, see {@code ClaimReport}). So coverage → branch is resolved here and
 * only then are the required document types read. It's what the missing-documents gate
 * ({@code ClassificationOrchestrator.checkRequiredDocuments}) uses to decide whether a case is
 * missing documentation — it used to come from the mock, now from what the referente configured.
 */
@Service
@RequiredArgsConstructor
public class InternalDocumentRequirementService {

    private final CoverageRepository coverageRepository;
    private final DocumentRequirementService documentRequirements;

    /**
     * Returns empty — not 404 — when the coverage doesn't exist or the branch has no schedule: the
     * engine composes this over its baseline, and a classification can't fall over missing config.
     */
    @Transactional(readOnly = true)
    public List<String> getByCoverage(Long coverageId) {
        Long branchId = coverageRepository.findById(coverageId)
                .map(Coverage::getBranchId)
                .orElse(null);
        if (branchId == null) {
            return List.of();
        }
        return documentRequirements.get(branchId);
    }
}
