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
 * by branch + claim cause with ids, but the claim reaching the engine only carries a
 * {@code coverageId} and the claim cause as a name (see {@code ClaimReport}). So coverage → branch
 * is resolved here, and the claim-cause name → its id within that branch, and only then are the
 * required document types read. It's what the missing-documents gate
 * ({@code ClassificationOrchestrator.checkRequiredDocuments}) uses to decide whether a case is
 * missing documentation — it used to come from the mock, now from what the referente configured.
 */
@Service
@RequiredArgsConstructor
public class InternalDocumentRequirementService {

    private final CoverageRepository coverageRepository;
    private final DocumentRequirementService documentRequirements;

    /**
     * The schedule the referente configured, or {@code null} when it couldn't be resolved at all —
     * unknown coverage, or a branch with no such claim cause.
     *
     * <p>The distinction matters: an <b>empty list</b> is an answer ("this claim cause needs no
     * documents"), {@code null} is the absence of one. The engine composes this over its baseline,
     * and folding both into an empty list made a referente who cleared every document from the
     * panel fall back to the baseline's — the panel showed none and the engine still demanded one.
     * Never 404: a classification can't fall over missing config.
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
